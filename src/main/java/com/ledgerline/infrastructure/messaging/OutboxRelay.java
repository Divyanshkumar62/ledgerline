package com.ledgerline.infrastructure.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polls the outbox and publishes to Kafka. Delivery is AT-LEAST-ONCE:
 *
 * <pre>
 * BEGIN
 *   SELECT ... WHERE published_at IS NULL ORDER BY sequence_number LIMIT n FOR UPDATE SKIP LOCKED
 *   send every row to Kafka, then wait for the acks in order    ← stops at the first failure
 *   UPDATE published_at for the rows that were acked
 * COMMIT
 * </pre>
 *
 * If the process dies after Kafka acked but before COMMIT, those rows are sent again next time.
 * That duplicate is the price of never losing an event; consumers deduplicate on {@code event_id}.
 * SKIP LOCKED lets several relay instances share the table without blocking on each other's batch.
 * Stopping at the first failure (instead of skipping ahead) keeps per-key order for a single relay.
 */
@Component
@ConditionalOnProperty(name = "ledgerline.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final long sendTimeoutMs;
    private final Counter eventsPublished;
    private final Counter publishFailures;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, PlatformTransactionManager transactionManager,
                       @Value("${ledgerline.outbox.relay.batch-size:100}") int batchSize,
                       @Value("${ledgerline.outbox.relay.send-timeout-ms:5000}") long sendTimeoutMs,
                       MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
        this.eventsPublished = meterRegistry.counter("ledger.outbox.published");
        this.publishFailures = meterRegistry.counter("ledger.outbox.publish.failures");
    }

    @Scheduled(fixedDelayString = "${ledgerline.outbox.relay.poll-interval-ms:200}")
    public void poll() {
        try {
            int published;
            do {
                published = relayBatch();
            } while (published == batchSize); // drain a backlog without waiting for the next tick
        } catch (RuntimeException e) {
            log.warn("Outbox relay pass failed; unpublished events stay in the outbox: {}", e.toString());
        }
    }

    /** @return number of events published in this batch (fewer than the batch size means stop for now) */
    int relayBatch() {
        Integer published = transactionTemplate.execute(status -> {
            List<OutboxRow> rows = jdbc.query("""
                    SELECT sequence_number, event_id, event_type, topic, event_key, journal_entry_id, payload, created_at
                      FROM outbox_events
                     WHERE published_at IS NULL
                     ORDER BY sequence_number
                     LIMIT ?
                       FOR UPDATE SKIP LOCKED
                    """, (rs, i) -> new OutboxRow(
                    rs.getLong("sequence_number"), rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                    rs.getString("topic"), rs.getString("event_key"), rs.getObject("journal_entry_id", UUID.class),
                    rs.getString("payload"), rs.getTimestamp("created_at")), batchSize);

            // Pipelined: hand the whole batch to the producer, then wait for the acks in order. The idempotent
            // producer keeps per-partition order, so the acked PREFIX is exactly what may be marked published.
            List<CompletableFuture<?>> inFlight = new ArrayList<>(rows.size());
            for (OutboxRow row : rows) {
                try {
                    inFlight.add(kafka.send(row.toRecord()));
                } catch (KafkaException e) { // send() can fail synchronously, e.g. no metadata while the broker is down
                    log.warn("Kafka refused event {} (seq {}); will retry: {}", row.eventId(), row.sequence(), e.toString());
                    break;
                }
            }
            List<Object[]> acked = new ArrayList<>(inFlight.size());
            for (int i = 0; i < inFlight.size() && awaitAck(inFlight.get(i), rows.get(i)); i++) {
                acked.add(new Object[]{rows.get(i).sequence()});
            }
            jdbc.batchUpdate("UPDATE outbox_events SET published_at = clock_timestamp() WHERE sequence_number = ?", acked);
            eventsPublished.increment(acked.size());
            if (acked.size() < rows.size()) {
                publishFailures.increment();
            }
            return acked.size() == rows.size() ? rows.size() : -1;
        });
        return published == null ? 0 : published;
    }

    private boolean awaitAck(CompletableFuture<?> ack, OutboxRow row) {
        try {
            ack.get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Kafka did not ack event {} (seq {}); will retry: {}", row.eventId(), row.sequence(), e.toString());
            return false;
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    record OutboxRow(long sequence, UUID eventId, String type, String topic, String key, UUID journalEntryId,
                     String payload, Timestamp createdAt) {

        ProducerRecord<String, String> toRecord() {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, envelope());
            record.headers().add("event_id", bytes(eventId.toString()));
            record.headers().add("event_type", bytes(type));
            record.headers().add("sequence", bytes(Long.toString(sequence)));
            if (journalEntryId != null) {
                record.headers().add("journal_entry_id", bytes(journalEntryId.toString()));
            }
            return record;
        }

        /** Self-describing message: consumers that ignore headers still get the dedup id and order hint. */
        String envelope() {
            return """
                    {"eventId":"%s","sequence":%d,"type":"%s","journalEntryId":%s,"occurredAt":"%s","data":%s}"""
                    .formatted(eventId, sequence, type,
                            journalEntryId == null ? "null" : "\"" + journalEntryId + "\"",
                            createdAt.toInstant(), payload);
        }
    }
}
