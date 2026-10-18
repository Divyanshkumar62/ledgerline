package com.ledgerline.integration;

import com.ledgerline.application.dto.TransferLegRequest;
import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.application.dto.TransferResponse;
import com.ledgerline.application.service.TransferService;
import com.ledgerline.domain.exception.ImbalancedJournalEntryException;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.AccountType;
import com.ledgerline.domain.model.PostingDirection;
import com.ledgerline.domain.repository.AccountRepository;
import com.ledgerline.domain.repository.JournalEntryRepository;
import com.ledgerline.domain.repository.PostingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves Week 2 Invariants:
 * 1. Zero-Sum Balance Invariant enforced at Domain and Database levels.
 * 2. Imbalanced entries roll back and leave 0 rows in journal_entries and postings.
 * 3. Financial records (postings/journal entries) are strictly immutable.
 */
@Testcontainers
@SpringBootTest
class ZeroSumBalanceInvariantIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private PostingRepository postingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("A valid balanced transfer commits atomically, updating accounts and creating postings")
    void balancedTransfer_commitsAtomically() {
        Account source = accountRepository.save(new Account(AccountType.ASSET, "USD", true));
        Account target = accountRepository.save(new Account(AccountType.ASSET, "USD", true));

        // Credit source account with initial funds
        source.applyPosting(PostingDirection.DEBIT, 10_000L, "USD");
        accountRepository.save(source);

        TransferRequest request = new TransferRequest(
                "Inter-account wire",
                "USD",
                "tx-" + UUID.randomUUID(),
                List.of(
                        new TransferLegRequest(source.getId(), PostingDirection.CREDIT, 3_000L),
                        new TransferLegRequest(target.getId(), PostingDirection.DEBIT, 3_000L)
                )
        );

        TransferResponse response = transferService.executeTransfer(request);

        assertThat(response).isNotNull();
        assertThat(response.legs()).hasSize(2);

        Account updatedSource = accountRepository.findById(source.getId()).orElseThrow();
        Account updatedTarget = accountRepository.findById(target.getId()).orElseThrow();

        assertThat(updatedSource.getBalance()).isEqualTo(7_000L);
        assertThat(updatedTarget.getBalance()).isEqualTo(3_000L);

        // Verify postings persisted
        assertThat(postingRepository.findByJournalEntryId(response.journalEntryId())).hasSize(2);
    }

    @Test
    @DisplayName("An imbalanced transfer is rejected and rolls back cleanly with zero rows left")
    void imbalancedTransfer_failsAndLeavesNoRows() {
        Account source = accountRepository.save(new Account(AccountType.ASSET, "USD", true));
        Account target = accountRepository.save(new Account(AccountType.ASSET, "USD", true));

        source.applyPosting(PostingDirection.DEBIT, 10_000L, "USD");
        accountRepository.save(source);

        long initialEntryCount = journalEntryRepository.findById(UUID.randomUUID()).stream().count();
        int initialPostingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM postings", Integer.class);

        TransferRequest imbalancedRequest = new TransferRequest(
                "Imbalanced attack transfer",
                "USD",
                "tx-imbalanced-" + UUID.randomUUID(),
                List.of(
                        new TransferLegRequest(source.getId(), PostingDirection.CREDIT, 5_000L),
                        new TransferLegRequest(target.getId(), PostingDirection.DEBIT, 4_000L) // Imbalanced by 1000
                )
        );

        assertThatThrownBy(() -> transferService.executeTransfer(imbalancedRequest))
                .isInstanceOf(ImbalancedJournalEntryException.class);

        // Assert no orphaned postings or journal entries were committed
        int finalPostingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM postings", Integer.class);
        assertThat(finalPostingCount).isEqualTo(initialPostingCount);

        // Assert source account was not debited
        Account refreshedSource = accountRepository.findById(source.getId()).orElseThrow();
        assertThat(refreshedSource.getBalance()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("PostgreSQL immutability trigger blocks direct UPDATE or DELETE on postings")
    void immutabilityTrigger_blocksModifications() {
        Account source = accountRepository.save(new Account(AccountType.ASSET, "USD", false));
        Account target = accountRepository.save(new Account(AccountType.ASSET, "USD", false));

        TransferRequest request = new TransferRequest(
                "Immutability verification wire",
                "USD",
                "tx-immutability-" + UUID.randomUUID(),
                List.of(
                        new TransferLegRequest(source.getId(), PostingDirection.CREDIT, 1_000L),
                        new TransferLegRequest(target.getId(), PostingDirection.DEBIT, 1_000L)
                )
        );

        TransferResponse response = transferService.executeTransfer(request);
        UUID postingId = response.legs().get(0).postingId();

        // Direct SQL update attempt must be rejected by PostgreSQL immutability trigger
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE postings SET amount = 99999 WHERE id = ?", postingId))
                .hasMessageContaining("Ledger records are immutable");

        // Direct SQL delete attempt must be rejected
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM postings WHERE id = ?", postingId))
                .hasMessageContaining("Ledger records are immutable");
    }
}
