package com.ledgerline.integration;

import com.ledgerline.application.dto.TransferLegRequest;
import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.application.dto.TransferResponse;
import com.ledgerline.domain.exception.ImbalancedJournalEntryException;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.AccountType;
import com.ledgerline.domain.model.PostingDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves Week 2 Invariants:
 * 1. Zero-Sum Balance Invariant enforced at Domain AND Database levels (each tested on its own).
 * 2. Imbalanced entries roll back and leave 0 rows in journal_entries and postings.
 * 3. Financial records (postings/journal entries) are strictly immutable.
 */
class ZeroSumBalanceInvariantIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("A valid balanced transfer commits atomically, updating accounts and creating postings")
    void balancedTransfer_commitsAtomically() {
        Account source = fundedAccount(10_000L);
        Account target = newAccount();

        TransferResponse response = transferService.executeTransfer(transfer(source.getId(), target.getId(), 3_000L));

        assertThat(response.legs()).hasSize(2);
        assertThat(balanceOf(source.getId())).isEqualTo(7_000L);
        assertThat(balanceOf(target.getId())).isEqualTo(3_000L);
        assertThat(postingRepository.findByJournalEntryId(response.journalEntryId())).hasSize(2);
        assertBalanceMatchesPostings(source.getId());
        assertBalanceMatchesPostings(target.getId());
    }

    @Test
    @DisplayName("Domain layer: an imbalanced transfer is rejected and leaves zero rows")
    void imbalancedTransfer_failsAndLeavesNoRows() {
        Account source = fundedAccount(10_000L);
        Account target = newAccount();
        int postingsBefore = countPostings();

        TransferRequest imbalanced = new TransferRequest("Imbalanced attack transfer", "USD", List.of(
                new TransferLegRequest(source.getId(), PostingDirection.CREDIT, 5_000L),
                new TransferLegRequest(target.getId(), PostingDirection.DEBIT, 4_000L)));

        assertThatThrownBy(() -> transferService.executeTransfer(imbalanced))
                .isInstanceOf(ImbalancedJournalEntryException.class);

        assertThat(countPostings()).isEqualTo(postingsBefore);
        assertThat(balanceOf(source.getId())).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("Database layer: the deferred trigger rejects imbalanced postings at COMMIT, even bypassing Java")
    void deferredTrigger_rejectsImbalancedEntryAtCommit() throws SQLException {
        Account a = newAccount();
        Account b = newAccount();
        UUID entryId = UUID.randomUUID();

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            insertEntry(c, entryId);
            insertPosting(c, entryId, a.getId(), "DEBIT", 500);
            insertPosting(c, entryId, b.getId(), "CREDIT", 400); // both INSERTs succeed: the check is deferred

            assertThatThrownBy(c::commit).hasMessageContaining("Zero-sum balance invariant violated");
            c.rollback();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE id = ?", Integer.class, entryId))
                .isZero();
    }

    @Test
    @DisplayName("Database layer: zero-sum is per currency, so USD +500 / EUR -500 does NOT balance")
    void deferredTrigger_rejectsCrossCurrencyNetting() throws SQLException {
        Account usd = newAccount();
        Account eur = accountRepository.save(new Account(AccountType.ASSET, "EUR", true));
        UUID entryId = UUID.randomUUID();

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            insertEntry(c, entryId);
            insertPosting(c, entryId, usd.getId(), "DEBIT", 500);
            insertPosting(c, entryId, eur.getId(), "CREDIT", 500, "EUR"); // nets to 0 only if currencies are ignored

            assertThatThrownBy(c::commit).hasMessageContaining("Zero-sum balance invariant violated").hasMessageContaining(" in ");
            c.rollback();
        }
    }

    @Test
    @DisplayName("Database layer: a posting in a different currency from its account is rejected at COMMIT")
    void deferredTrigger_rejectsPostingCurrencyMismatch() throws SQLException {
        Account a = newAccount();
        Account b = newAccount();
        UUID entryId = UUID.randomUUID();

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            insertEntry(c, entryId);
            insertPosting(c, entryId, a.getId(), "DEBIT", 500, "EUR"); // USD account, EUR posting
            insertPosting(c, entryId, b.getId(), "CREDIT", 500, "EUR");

            assertThatThrownBy(c::commit).hasMessageContaining("does not match its account currency");
            c.rollback();
        }
    }

    @Test
    @DisplayName("Database layer: the deferred trigger rejects a single-leg entry at COMMIT")
    void deferredTrigger_rejectsSingleLegEntry() throws SQLException {
        Account a = newAccount();
        UUID entryId = UUID.randomUUID();

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            insertEntry(c, entryId);
            insertPosting(c, entryId, a.getId(), "DEBIT", 500);

            assertThatThrownBy(c::commit).hasMessageContaining("must have at least 2 legs");
            c.rollback();
        }
    }

    @Test
    @DisplayName("PostgreSQL immutability trigger blocks direct UPDATE or DELETE on postings and journal entries")
    void immutabilityTrigger_blocksModifications() {
        Account source = accountRepository.save(new Account(AccountType.ASSET, "USD", false));
        Account target = newAccount();

        TransferResponse response = transferService.executeTransfer(transfer(source.getId(), target.getId(), 1_000L));
        UUID postingId = response.legs().get(0).postingId();

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE postings SET amount = 99999 WHERE id = ?", postingId))
                .hasMessageContaining("Ledger records are immutable");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM postings WHERE id = ?", postingId))
                .hasMessageContaining("Ledger records are immutable");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE journal_entries SET description = 'x' WHERE id = ?", response.journalEntryId()))
                .hasMessageContaining("Ledger records are immutable");
    }

    private int countPostings() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM postings", Integer.class);
    }

    private static void insertEntry(Connection c, UUID entryId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO journal_entries (id, description, currency) VALUES (?, 'raw sql', 'USD')")) {
            ps.setObject(1, entryId);
            ps.executeUpdate();
        }
    }

    private static void insertPosting(Connection c, UUID entryId, UUID accountId, String direction, long amount) throws SQLException {
        insertPosting(c, entryId, accountId, direction, amount, "USD");
    }

    private static void insertPosting(Connection c, UUID entryId, UUID accountId, String direction, long amount, String currency)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO postings (journal_entry_id, account_id, direction, amount, currency) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, entryId);
            ps.setObject(2, accountId);
            ps.setString(3, direction);
            ps.setLong(4, amount);
            ps.setString(5, currency);
            ps.executeUpdate();
        }
    }
}
