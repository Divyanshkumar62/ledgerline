package com.ledgerline.integration;

import com.ledgerline.application.dto.TransferOutcome;
import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.domain.exception.IdempotencyKeyReusedException;
import com.ledgerline.domain.exception.InsufficientFundsException;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.AccountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Week 4 proof: a client retrying with the same Idempotency-Key and payload causes at most
 * one ledger effect, even when all retries arrive at the same instant.
 */
class IdempotencyDuplicateHammerTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("500 concurrent duplicates → exactly 1 journal entry and 499 replays of the same response")
    void hammer_500DuplicatesProduceExactlyOneLedgerEffect() throws Exception {
        String client = newClient().clientId();
        Account source = fundedAccount(1_000_000L, client);
        Account target = newAccount(client);
        TransferRequest request = transfer(source.getId(), target.getId(), 2_500L);
        Queue<TransferOutcome> outcomes = new ConcurrentLinkedQueue<>();

        Outcomes run = runConcurrently(POOL_SIZE, 500, i -> outcomes.add(transferService.executeIdempotent(client, "k-1", request)));

        assertThat(run.failures()).isEmpty();
        assertThat(outcomes).hasSize(500);
        assertThat(outcomes).filteredOn(TransferOutcome::replayed).hasSize(499);
        assertThat(outcomes).extracting(o -> o.response().journalEntryId()).containsOnly(outcomes.peek().response().journalEntryId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE idempotency_reference = ?", Integer.class, client + ":k-1"))
                .isEqualTo(1);
        assertThat(balanceOf(source.getId())).isEqualTo(1_000_000L - 2_500L);
        assertThat(balanceOf(target.getId())).isEqualTo(2_500L);
        assertBalanceMatchesPostings(source.getId());
        assertBalanceMatchesPostings(target.getId());
    }

    @Test
    @DisplayName("Same key with a different payload is rejected and nothing moves twice")
    void sameKeyDifferentPayload_isRejected() {
        String client = newClient().clientId();
        Account source = fundedAccount(10_000L, client);
        Account target = newAccount(client);

        transferService.executeIdempotent(client, "k-1", transfer(source.getId(), target.getId(), 1_000L));

        assertThatThrownBy(() -> transferService.executeIdempotent(client, "k-1", transfer(source.getId(), target.getId(), 9_000L)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(balanceOf(target.getId())).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("Keys are scoped per client: two clients may use the same key independently")
    void sameKeyDifferentClients_areIndependent() {
        String clientA = newClient().clientId();
        String clientB = newClient().clientId();
        Account sourceA = fundedAccount(10_000L, clientA);
        Account targetA = newAccount(clientA);
        Account sourceB = fundedAccount(10_000L, clientB);
        Account targetB = newAccount(clientB);

        TransferOutcome first = transferService.executeIdempotent(clientA, "shared-key", transfer(sourceA.getId(), targetA.getId(), 1_000L));
        TransferOutcome second = transferService.executeIdempotent(clientB, "shared-key", transfer(sourceB.getId(), targetB.getId(), 1_000L));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isFalse();
        assertThat(balanceOf(targetA.getId())).isEqualTo(1_000L);
        assertThat(balanceOf(targetB.getId())).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("A failed transfer rolls back its claim, so a later retry with the same key is a fresh attempt")
    void failedTransfer_releasesKey() {
        String client = newClient().clientId();
        Account source = newAccount(client); // empty, negative balance not allowed
        Account target = newAccount(client);
        TransferRequest request = transfer(source.getId(), target.getId(), 500L);

        assertThatThrownBy(() -> transferService.executeIdempotent(client, "k-1", request))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE client_id = ?", Integer.class, client)).isZero();

        Account capital = accountRepository.save(new Account(AccountType.EQUITY, "USD", false));
        transferService.executeTransfer(transfer(capital.getId(), source.getId(), 500L));

        TransferOutcome retried = transferService.executeIdempotent(client, "k-1", request);
        assertThat(retried.replayed()).isFalse();
        assertThat(balanceOf(target.getId())).isEqualTo(500L);
    }
}
