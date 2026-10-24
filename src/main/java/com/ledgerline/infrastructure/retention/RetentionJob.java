package com.ledgerline.infrastructure.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Housekeeping (ADR-009). Ledger tables are never touched: journal entries and postings are permanent.
 *
 * <ul>
 *   <li>Outbox rows are deleted once they have been PUBLISHED for longer than the retention.
 *       Unpublished rows are never deleted, however old: that would lose an event.</li>
 *   <li>Idempotency records are deleted after their retention. A retry with a purged key executes
 *       again, so the retention must exceed any client's retry window.
 *       {@code journal_entries.idempotency_reference} keeps the client:key trace forever.</li>
 * </ul>
 *
 * Deletes run in small batches (each its own short statement) so they never hold locks for long.
 * Set {@code ledgerline.retention.cron=-} to disable.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    private static final int BATCH = 5_000;

    private final JdbcTemplate jdbc;
    private final Duration outboxRetention;
    private final Duration idempotencyRetention;

    public RetentionJob(JdbcTemplate jdbc,
                        @Value("${ledgerline.retention.outbox-published:7d}") Duration outboxRetention,
                        @Value("${ledgerline.retention.idempotency:7d}") Duration idempotencyRetention) {
        this.jdbc = jdbc;
        this.outboxRetention = outboxRetention;
        this.idempotencyRetention = idempotencyRetention;
    }

    @Scheduled(cron = "${ledgerline.retention.cron:0 17 * * * *}")
    public void run() {
        Instant now = Instant.now();
        int outbox = purgePublishedOutbox(now.minus(outboxRetention));
        int keys = purgeIdempotencyRecords(now.minus(idempotencyRetention));
        log.info("Retention: deleted {} published outbox events, {} idempotency records", outbox, keys);
    }

    public int purgePublishedOutbox(Instant publishedBefore) {
        return deleteInBatches("""
                DELETE FROM outbox_events WHERE sequence_number IN (
                    SELECT sequence_number FROM outbox_events
                     WHERE published_at IS NOT NULL AND published_at < ?
                     LIMIT ?)
                """, publishedBefore);
    }

    public int purgeIdempotencyRecords(Instant createdBefore) {
        return deleteInBatches("""
                DELETE FROM idempotency_records WHERE (client_id, idempotency_key) IN (
                    SELECT client_id, idempotency_key FROM idempotency_records
                     WHERE created_at < ?
                     LIMIT ?)
                """, createdBefore);
    }

    private int deleteInBatches(String sql, Instant cutoff) {
        OffsetDateTime utcCutoff = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC);
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update(sql, utcCutoff, BATCH);
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }
}
