package com.ledgerline.infrastructure.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Outbox health, sampled from the table on every scrape (the backlog query uses the partial
 * "unpublished" index). The age gauge is the one to alert on: a backlog that drains is fine,
 * an oldest event that keeps getting older means the relay or Kafka is stuck.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        Gauge.builder("ledger.outbox.backlog", jdbc,
                        j -> j.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL", Long.class))
                .description("Events written but not yet acknowledged by Kafka")
                .register(registry);
        Gauge.builder("ledger.outbox.oldest.unpublished", jdbc, j -> j.queryForObject("""
                        SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - MIN(created_at)), 0)
                          FROM outbox_events WHERE published_at IS NULL
                        """, Double.class))
                .description("Age of the oldest unpublished event")
                .baseUnit("seconds")
                .register(registry);
    }
}
