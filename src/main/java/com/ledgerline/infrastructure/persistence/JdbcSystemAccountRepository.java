package com.ledgerline.infrastructure.persistence;

import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.AccountType;
import com.ledgerline.domain.repository.SystemAccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Get-or-create in two statements that are safe under concurrency: ON CONFLICT on the partial unique index
 * (system_purpose, currency) makes a racing second creator wait for the first and then insert nothing,
 * after which both read the same row.
 */
@Repository
public class JdbcSystemAccountRepository implements SystemAccountRepository {

    private final JdbcTemplate jdbc;

    public JdbcSystemAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID findOrCreate(String purpose, String currency, AccountType type) {
        jdbc.update("""
                INSERT INTO accounts (id, type, currency, disallow_negative_balance, owner_client_id, system_purpose)
                VALUES (gen_random_uuid(), ?, ?, FALSE, ?, ?)
                ON CONFLICT (system_purpose, currency) WHERE system_purpose IS NOT NULL DO NOTHING
                """, type.name(), currency, Account.SYSTEM_OWNER, purpose);
        return jdbc.queryForObject("SELECT id FROM accounts WHERE system_purpose = ? AND currency = ?",
                UUID.class, purpose, currency);
    }
}
