package com.ledgerline.infrastructure.persistence;

import com.ledgerline.domain.event.AccountAdminActionTaken;
import com.ledgerline.domain.event.LedgerDriftDetected;
import com.ledgerline.domain.event.LedgerEvent;
import com.ledgerline.domain.event.TransferCompleted;
import com.ledgerline.domain.repository.OutboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Writes outbox rows over JDBC on the transaction's own connection, so the row commits or rolls back
 * with the ledger change that produced it. Publishing is the relay's job, never this class's.
 */
@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    public static final String TRANSFERS_TOPIC = "ledger.transfers.v1";
    public static final String ACCOUNTS_TOPIC = "ledger.accounts.v1";

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcOutboxRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void append(LedgerEvent event) {
        jdbc.update("""
                INSERT INTO outbox_events (event_id, event_type, topic, event_key, journal_entry_id, payload)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), event.type(), topicOf(event), event.aggregateKey(), event.journalEntryId(),
                json.writeValueAsString(event));
    }

    private static String topicOf(LedgerEvent event) {
        return switch (event) {
            case TransferCompleted t -> TRANSFERS_TOPIC;
            case LedgerDriftDetected d -> ACCOUNTS_TOPIC;
            case AccountAdminActionTaken a -> ACCOUNTS_TOPIC;
        };
    }
}
