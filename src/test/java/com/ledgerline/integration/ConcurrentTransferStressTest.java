package com.ledgerline.integration;

import com.ledgerline.application.strategy.ConcurrencyStrategyType;
import com.ledgerline.domain.exception.InsufficientFundsException;
import com.ledgerline.domain.model.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 3 proof: under 500 concurrent transfers on one shared account, every strategy keeps
 * final balances equal to the sum of committed postings. Requests may be REJECTED under
 * contention (strategy B, after its retries), but none may be lost or applied twice.
 */
class ConcurrentTransferStressTest extends PostgresIntegrationTest {

    private static final int TRANSFERS = 500;
    private static final long AMOUNT = 100L;

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConcurrencyStrategyType.class)
    @DisplayName("Hot account storm: no lost updates, no drift")
    void hotAccountStorm_hasNoLostUpdates(ConcurrencyStrategyType strategy) throws Exception {
        long initial = 1_000_000L;
        Account hot = fundedAccount(initial);
        List<UUID> targets = IntStream.range(0, 10).mapToObj(i -> newAccount().getId()).toList();

        Outcomes out = runConcurrently(strategy, POOL_SIZE, TRANSFERS,
                i -> transfer(hot.getId(), targets.get(i % targets.size()), AMOUNT));

        // The only acceptable failure is a conflict that exhausted strategy B's retries.
        assertThat(out.failures()).allMatch(OptimisticLockingFailureException.class::isInstance);
        if (strategy != ConcurrencyStrategyType.OPTIMISTIC_LOCKING) {
            assertThat(out.failures()).isEmpty();
        }

        long moved = out.succeeded() * AMOUNT;
        assertThat(balanceOf(hot.getId())).isEqualTo(initial - moved);
        assertThat(targets.stream().mapToLong(this::balanceOf).sum()).isEqualTo(moved);
        assertBalanceMatchesPostings(hot.getId());
        targets.forEach(this::assertBalanceMatchesPostings);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConcurrencyStrategyType.class)
    @DisplayName("Overdraft race: 500 withdrawals against funds for 100 never spend money that is not there")
    void overdraftRace_neverOverspends(ConcurrencyStrategyType strategy) throws Exception {
        int affordable = 100;
        Account hot = fundedAccount(affordable * AMOUNT);
        UUID sink = newAccount().getId();

        Outcomes out = runConcurrently(strategy, POOL_SIZE, TRANSFERS, i -> transfer(hot.getId(), sink, AMOUNT));

        assertThat(out.failures()).allMatch(f -> f instanceof InsufficientFundsException
                || f instanceof OptimisticLockingFailureException);
        if (strategy == ConcurrencyStrategyType.OPTIMISTIC_LOCKING) {
            assertThat(out.succeeded()).isLessThanOrEqualTo(affordable); // some may lose on retries
        } else {
            assertThat(out.succeeded()).isEqualTo(affordable);           // serialised: exactly the funds available
        }

        assertThat(balanceOf(hot.getId())).isEqualTo((affordable - out.succeeded()) * AMOUNT).isNotNegative();
        assertThat(balanceOf(sink)).isEqualTo(out.succeeded() * AMOUNT);
        assertBalanceMatchesPostings(hot.getId());
        assertBalanceMatchesPostings(sink);
    }
}
