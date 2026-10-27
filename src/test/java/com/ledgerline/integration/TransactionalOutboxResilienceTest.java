package com.ledgerline.integration;

import com.ledgerline.domain.model.Account;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Week 5 proof: Kafka is not on the transfer path. With the broker down for 30 s, transfers keep
 * committing, their events wait in the outbox, and every one of them is published after recovery.
 */
@DirtiesContext // the relay runs in this context only; stop it when the class is done
class TransactionalOutboxResilienceTest extends PostgresIntegrationTest {

    private static final String TOPIC = "ledger.transfers.v1";
    private static final Duration OUTAGE = Duration.ofSeconds(30);

    static final KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.8.0");

    static {
        kafka.start();
    }

    @DynamicPropertySource
    static void kafkaAndRelay(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("ledgerline.outbox.relay.enabled", () -> "true");
        registry.add("ledgerline.outbox.relay.poll-interval-ms", () -> "100");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Broker down 30s during transfers: no transfer fails, no event is lost")
    void kafkaOutage_noEventIsLost() throws Exception {
        Account source = fundedAccount(1_000_000L);
        Account target = newAccount();
        List<UUID> expected = new ArrayList<>();

        // 1. Broker up: events flow through.
        for (int i = 0; i < 20; i++) {
            expected.add(transferService.executeTransfer(transfer(source.getId(), target.getId(), 10)).journalEntryId());
        }
        await().atMost(Duration.ofSeconds(60)).until(() -> unpublished(expected) == 0);

        // 2. Broker down: transfers still commit; events pile up in the outbox.
        pause(true);
        long outageStart = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            expected.add(transferService.executeTransfer(transfer(source.getId(), target.getId(), 10)).journalEntryId());
        }
        Thread.sleep(Math.max(0, OUTAGE.toMillis() - Duration.ofNanos(System.nanoTime() - outageStart).toMillis()));
        assertThat(unpublished(expected)).as("events buffered during the outage").isEqualTo(50);

        // 3. Broker back: the relay drains the backlog.
        pause(false);
        await().atMost(Duration.ofSeconds(60)).until(() -> unpublished(expected) == 0);

        Map<UUID, Set<String>> eventIdsByEntry = consumeUntilSeen(expected, Duration.ofSeconds(60));
        assertThat(eventIdsByEntry.keySet()).as("every committed transfer reached Kafka").containsAll(expected);
        // At-least-once: a re-send would reuse the SAME event_id, so after dedup there is exactly one event per transfer.
        expected.forEach(entry -> assertThat(eventIdsByEntry.get(entry)).hasSize(1));
        assertThat(balanceOf(target.getId())).isEqualTo(70 * 10L);
    }

    /** Only this test's events: the shared database also holds backlog from test classes that ran without a relay. */
    private int unpublished(List<UUID> journalEntryIds) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL AND journal_entry_id = ANY (?)",
                Integer.class, (Object) journalEntryIds.toArray(UUID[]::new));
    }

    private static void pause(boolean down) {
        var docker = kafka.getDockerClient();
        if (down) {
            docker.pauseContainerCmd(kafka.getContainerId()).exec();
        } else {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }
    }

    /** Reads the topic from the beginning, grouping event ids by journal entry, until every expected entry is seen. */
    private static Map<UUID, Set<String>> consumeUntilSeen(List<UUID> expected, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "verifier-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        Map<UUID, Set<String>> seen = new HashMap<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            while (!seen.keySet().containsAll(expected) && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    Header entry = record.headers().lastHeader("journal_entry_id");
                    Header eventId = record.headers().lastHeader("event_id");
                    seen.computeIfAbsent(UUID.fromString(new String(entry.value(), StandardCharsets.UTF_8)), k -> new HashSet<>())
                            .add(new String(eventId.value(), StandardCharsets.UTF_8));
                }
            }
        }
        return seen;
    }
}
