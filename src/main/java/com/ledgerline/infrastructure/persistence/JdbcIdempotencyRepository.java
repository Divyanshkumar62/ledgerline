package com.ledgerline.infrastructure.persistence;

import com.ledgerline.domain.model.IdempotencyRecord;
import com.ledgerline.domain.repository.IdempotencyRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Plain JDBC, not JPA: the claim must hit the database immediately (Hibernate would defer an
 * insert until flush), and ON CONFLICT has no JPQL equivalent. JpaTransactionManager exposes its
 * connection to JdbcTemplate, so these statements run in the same transaction as the transfer.
 */
@Repository
public class JdbcIdempotencyRepository implements IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Unlike a plain INSERT, a conflict here does not abort the transaction, so we can still
    // read the existing record afterwards. If the conflicting row is uncommitted, PostgreSQL waits
    // for that transaction: commit -> 0 rows inserted (replay); rollback -> our row is inserted.
    @Override
    public boolean claim(String clientId, String key, String requestHash) {
        return jdbc.update("""
                INSERT INTO idempotency_records (client_id, idempotency_key, request_hash)
                VALUES (?, ?, ?)
                ON CONFLICT (client_id, idempotency_key) DO NOTHING
                """, clientId, key, requestHash) == 1;
    }

    @Override
    public Optional<IdempotencyRecord> find(String clientId, String key) {
        return jdbc.query("""
                SELECT client_id, idempotency_key, request_hash, journal_entry_id
                  FROM idempotency_records
                 WHERE client_id = ? AND idempotency_key = ?
                """, (rs, i) -> new IdempotencyRecord(
                rs.getString("client_id"), rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getObject("journal_entry_id", UUID.class)),
                clientId, key).stream().findFirst();
    }

    @Override
    public void complete(String clientId, String key, UUID journalEntryId) {
        jdbc.update("UPDATE idempotency_records SET journal_entry_id = ? WHERE client_id = ? AND idempotency_key = ?",
                journalEntryId, clientId, key);
    }
}
