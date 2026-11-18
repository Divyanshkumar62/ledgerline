package com.ledgerline.integration;

import com.ledgerline.application.dto.AuditReport;
import com.ledgerline.application.dto.TransferOutcome;
import com.ledgerline.application.service.LedgerAuditService;
import com.ledgerline.application.strategy.ConcurrencyStrategy;
import com.ledgerline.application.strategy.ConcurrencyStrategyType;
import com.ledgerline.application.strategy.LedgerCommand;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.PostingDirection;
import com.ledgerline.domain.repository.StripeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-014 proof: a storm of concurrent credits into one striped account conserves every unit of money, keeps
 * every stripe's cached balance equal to its postings, never touches the parent, and cannot deadlock.
 */
class StripedAccountStressTest extends PostgresIntegrationTest {

    private static final int TRANSFERS = 500;
    private static final int STRIPES = 8;
    private static final long AMOUNT = 100L;
    private static final long RICH = 1_000_000_000L;

    @Autowired
    private StripeRepository stripeRepository;

    @Autowired
    private LedgerAuditService auditService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private List<ConcurrencyStrategy> strategies;

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConcurrencyStrategyType.class)
    @DisplayName("500 concurrent credits into one 8-stripe account: money conserved, no drift, parent untouched, no deadlock")
    void creditStorm_conservesMoney(ConcurrencyStrategyType strategy) throws Exception {
        Account sink = stripedAccount(STRIPES, Account.SYSTEM_OWNER);
        List<UUID> sources = IntStream.range(0, 50).mapToObj(i -> fundedAccount(RICH).getId()).toList();

        Outcomes out = runConcurrently(strategy, POOL_SIZE, TRANSFERS,
                i -> transfer(sources.get(i % sources.size()), sink.getId(), AMOUNT));

        assertThat(out.deadlocks()).as("deadlocks").isZero();
        // Strategy B may give up on a contended SOURCE after its retries; nothing else may fail.
        assertThat(out.failures()).allMatch(OptimisticLockingFailureException.class::isInstance);
        if (strategy != ConcurrencyStrategyType.OPTIMISTIC_LOCKING) {
            assertThat(out.failures()).isEmpty();
        }

        long credited = out.succeeded() * AMOUNT;
        List<UUID> stripes = stripeRepository.findStripeIds(sink.getId());
        assertThat(stripes).hasSize(STRIPES);
        assertThat(stripes.stream().mapToLong(this::balanceOf).sum()).as("Σ stripes").isEqualTo(credited);
        assertThat(stripeRepository.sumStripeBalances(sink.getId())).as("parent's reported balance").isEqualTo(credited);
        assertThat(sources.stream().mapToLong(this::balanceOf).sum()).as("Σ sources").isEqualTo(sources.size() * RICH - credited);

        stripes.forEach(this::assertBalanceMatchesPostings);
        assertThat(balanceOf(sink.getId())).as("parent cached balance").isZero();
        assertThat(postingsOn(sink.getId())).as("postings on the parent itself").isZero();
        assertThat(stripes.stream().filter(s -> postingsOn(s) > 0).count()).as("stripes that received load").isEqualTo(STRIPES);

        List<UUID> audited = new ArrayList<>(stripes);
        audited.add(sink.getId());
        AuditReport audit = auditService.audit(audited, Instant.now());
        assertThat(audit.discrepancies()).as("audit discrepancies").isEmpty();
    }

    @Test
    @DisplayName("Idempotent retry of a credit into a striped account replays the original: same entry, same stripe, one effect")
    void idempotentRetry_replaysOnSameStripe() {
        Credentials client = newClient();
        UUID source = fundedAccount(10_000, client.clientId()).getId();
        UUID sink = stripedAccount(STRIPES, client.clientId()).getId();

        TransferOutcome first = transferService.executeIdempotent(client.clientId(), "fee-1", transfer(source, sink, AMOUNT));
        TransferOutcome retry = transferService.executeIdempotent(client.clientId(), "fee-1", transfer(source, sink, AMOUNT));

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.response().journalEntryId()).isEqualTo(first.response().journalEntryId());
        assertThat(retry.response().legs()).isEqualTo(first.response().legs());
        assertThat(stripeRepository.findStripeIds(sink)).contains(first.response().legs().get(1).accountId());
        assertThat(stripeRepository.sumStripeBalances(sink)).isEqualTo(AMOUNT);
    }

    @Test
    @DisplayName("Mutation check: bypass the router and post to the parent directly → the database trigger rejects it")
    void databaseRejectsPostingOnParent() {
        UUID source = fundedAccount(10_000).getId();
        UUID sink = stripedAccount(STRIPES, Account.SYSTEM_OWNER).getId();
        ConcurrencyStrategy pessimistic = strategies.stream()
                .filter(s -> s.getType() == ConcurrencyStrategyType.ORDERED_PESSIMISTIC).findFirst().orElseThrow();
        LedgerCommand unrouted = new LedgerCommand("bypass", "USD", List.of(
                new LedgerCommand.Leg(source, PostingDirection.CREDIT, AMOUNT),
                new LedgerCommand.Leg(sink, PostingDirection.DEBIT, AMOUNT)));

        assertThatThrownBy(() -> pessimistic.executeTransfer(unrouted, null))
                .rootCause().hasMessageContaining("is striped: postings must go to one of its stripes");
        assertThat(balanceOf(source)).as("rolled back").isEqualTo(10_000);
        assertThat(postingsOn(sink)).isZero();
    }

    @Test
    @DisplayName("The database refuses a balance check on a striped account or its stripes, and more than 64 stripes")
    void databaseEnforcesStripeStructure() {
        Account parent = stripedAccount(2, Account.SYSTEM_OWNER);
        UUID stripe = stripeRepository.findStripeIds(parent.getId()).get(0);

        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET disallow_negative_balance = TRUE WHERE id = ?", parent.getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_accounts_striped_no_balance_check");
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET disallow_negative_balance = TRUE WHERE id = ?", stripe))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_accounts_striped_no_balance_check");
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET stripe_count = 65 WHERE id = ?", parent.getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_accounts_stripe_count");
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET stripe_count = 2 WHERE id = ?", stripe))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_accounts_stripe_not_nested");
    }

    private long postingsOn(UUID accountId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM postings WHERE account_id = ?", Long.class, accountId);
        return count == null ? 0 : count;
    }
}
