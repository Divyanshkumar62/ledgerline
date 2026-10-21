package com.ledgerline.infrastructure.persistence;

import com.ledgerline.domain.model.PostingDirection;
import com.ledgerline.domain.model.PostingWindow;
import com.ledgerline.domain.model.ReconciliationCheckpoint;
import com.ledgerline.domain.repository.ReconciliationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcReconciliationRepository implements ReconciliationRepository {

    private final JdbcTemplate jdbc;

    public JdbcReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> allAccountIds() {
        return jdbc.queryForList("SELECT id FROM accounts ORDER BY id", UUID.class);
    }

    @Override
    public Optional<ReconciliationCheckpoint> findCheckpoint(UUID accountId) {
        return jdbc.query("""
                SELECT account_id, verified_balance, last_posting_seq, checked_at
                  FROM reconciliation_checkpoints WHERE account_id = ?
                """, (rs, i) -> new ReconciliationCheckpoint(
                rs.getObject("account_id", UUID.class), rs.getLong("verified_balance"),
                rs.getLong("last_posting_seq"), rs.getTimestamp("checked_at").toInstant()),
                accountId).stream().findFirst();
    }

    @Override
    public void saveCheckpoint(ReconciliationCheckpoint cp) {
        jdbc.update("""
                INSERT INTO reconciliation_checkpoints (account_id, verified_balance, last_posting_seq, checked_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (account_id) DO UPDATE
                   SET verified_balance = EXCLUDED.verified_balance,
                       last_posting_seq = EXCLUDED.last_posting_seq,
                       checked_at       = EXCLUDED.checked_at
                """, cp.accountId(), cp.verifiedBalance(), cp.lastPostingSeq(), utc(cp.checkedAt()));
    }

    /**
     * One pass over the postings after the checkpoint (index: account_id, seq):
     * the full delta (to verify the balance now) and the delta up to the newest posting older than
     * the horizon (to move the checkpoint). The horizon is cut on seq, not on time, so the new
     * checkpoint covers EXACTLY the postings with seq <= horizon_seq that this snapshot can see.
     */
    @Override
    public PostingWindow postingsAfter(UUID accountId, long afterSeq, PostingDirection normalBalance, Instant horizon) {
        return jdbc.queryForObject("""
                WITH since AS (
                    SELECT seq, created_at,
                           CASE WHEN direction = ? THEN amount ELSE -amount END AS signed
                      FROM postings
                     WHERE account_id = ? AND seq > ?
                ), cut AS (
                    SELECT MAX(seq) AS horizon_seq FROM since WHERE created_at < ?
                )
                SELECT COALESCE((SELECT SUM(signed) FROM since), 0)                          AS delta_since,
                       (SELECT horizon_seq FROM cut)                                         AS horizon_seq,
                       COALESCE((SELECT SUM(signed) FROM since, cut WHERE seq <= horizon_seq), 0) AS delta_to_horizon
                """, (rs, i) -> new PostingWindow(
                rs.getLong("delta_since"), rs.getObject("horizon_seq", Long.class), rs.getLong("delta_to_horizon")),
                normalBalance.name(), accountId, afterSeq, utc(horizon));
    }

    @Override
    public void freeze(UUID accountId) {
        jdbc.update("UPDATE accounts SET frozen = TRUE, version = version + 1, updated_at = clock_timestamp() WHERE id = ?",
                accountId);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
