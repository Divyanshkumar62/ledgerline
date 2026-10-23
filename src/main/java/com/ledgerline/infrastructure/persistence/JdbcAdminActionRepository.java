package com.ledgerline.infrastructure.persistence;

import com.ledgerline.domain.repository.AdminActionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class JdbcAdminActionRepository implements AdminActionRepository {

    private final JdbcTemplate jdbc;

    public JdbcAdminActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID accountId, String action, String reason, String actorClientId,
                       Long balanceBefore, Long balanceAfter, UUID journalEntryId) {
        jdbc.update("""
                INSERT INTO account_admin_actions (account_id, action, reason, actor_client_id, balance_before, balance_after, journal_entry_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, accountId, action, reason, actorClientId, balanceBefore, balanceAfter, journalEntryId);
    }
}
