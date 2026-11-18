package com.ledgerline.infrastructure.persistence;

import com.ledgerline.domain.model.PostingDirection;
import com.ledgerline.domain.model.StatementLine;
import com.ledgerline.domain.repository.StatementRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * One page of postings, newest first, with the balance after each one. Keyset pagination on
 * (account_id, seq) uses the audit index and stays fast at any depth, unlike OFFSET.
 *
 * balanceAfter(row) = current balance − Σ signed amounts of every posting NEWER than the row
 *                   = current − (postings newer than this page) − (rows above it on this page).
 */
@Repository
public class JdbcStatementRepository implements StatementRepository {

    private final JdbcTemplate jdbc;

    public JdbcStatementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ponytail: the "newer than this page" sum scans postings between the cursor and now; for very deep pages
    // of very busy accounts, derive it from the newest page's balanceAfter instead (client passes it back).
    @Override
    public List<StatementLine> page(List<UUID> accountIds, PostingDirection normalBalance, long currentBalance, Long beforeSeq, int limit) {
        long cursor = beforeSeq == null ? Long.MAX_VALUE : beforeSeq;
        String normal = normalBalance.name();
        // A plain account keeps the exact single-account predicate (an ordered scan of its (account_id, seq) index);
        // only a striped parent's statement uses ANY, which merges the stripes' postings.
        boolean single = accountIds.size() == 1;
        String accountPredicate = single ? "account_id = ?" : "account_id = ANY (?::uuid[])";
        Object accounts = single ? accountIds.get(0) : JdbcStripeRepository.uuidArray(accountIds);
        return jdbc.query("""
                WITH page AS (
                    SELECT p.seq, p.id, p.journal_entry_id, je.description, p.direction, p.amount, p.currency, p.created_at,
                           CASE WHEN p.direction = ? THEN p.amount ELSE -p.amount END AS signed
                      FROM postings p JOIN journal_entries je ON je.id = p.journal_entry_id
                     WHERE p.%1$s AND p.seq < ?
                     ORDER BY p.seq DESC
                     LIMIT ?
                ), newer AS (
                    SELECT COALESCE(SUM(CASE WHEN direction = ? THEN amount ELSE -amount END), 0) AS total
                      FROM postings WHERE %1$s AND seq >= ?
                )
                SELECT page.*,
                       ? - (SELECT total FROM newer)
                         - COALESCE(SUM(signed) OVER (ORDER BY seq DESC ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0)
                         AS balance_after
                  FROM page
                 ORDER BY seq DESC
                """.formatted(accountPredicate), (rs, i) -> new StatementLine(rs.getLong("seq"), rs.getObject("id", UUID.class),
                        rs.getObject("journal_entry_id", UUID.class), rs.getString("description"),
                        PostingDirection.valueOf(rs.getString("direction")), rs.getLong("amount"), rs.getString("currency"),
                        rs.getLong("signed"), rs.getLong("balance_after"), rs.getTimestamp("created_at").toInstant()),
                normal, accounts, cursor, limit, normal, accounts, cursor, currentBalance);
    }
}
