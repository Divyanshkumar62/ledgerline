package com.ledgerline.integration;

import com.ledgerline.application.strategy.ConcurrencyStrategyType;
import com.ledgerline.domain.model.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 3 proof: A->B and B->A transfers interleaved at high volume. Without a canonical lock
 * order this is the textbook deadlock (each transaction holds one row and waits for the other).
 * Every strategy must produce zero PostgreSQL 40P01 errors.
 */
class BidirectionalDeadlockTest extends PostgresIntegrationTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConcurrencyStrategyType.class)
    @DisplayName("A<->B storm produces zero deadlocks and conserves money")
    void bidirectionalStorm_producesZeroDeadlocks(ConcurrencyStrategyType strategy) throws Exception {
        long initial = 1_000_000L;
        Account a = fundedAccount(initial);
        Account b = fundedAccount(initial);

        Outcomes out = runConcurrently(strategy, POOL_SIZE, 500,
                i -> i % 2 == 0 ? transfer(a.getId(), b.getId(), 1) : transfer(b.getId(), a.getId(), 1));

        assertThat(out.deadlocks()).as("40P01 deadlocks").isZero();
        assertThat(out.failures()).allMatch(OptimisticLockingFailureException.class::isInstance);
        if (strategy != ConcurrencyStrategyType.OPTIMISTIC_LOCKING) {
            assertThat(out.failures()).isEmpty();
        }

        assertThat(balanceOf(a.getId()) + balanceOf(b.getId())).isEqualTo(2 * initial);
        assertBalanceMatchesPostings(a.getId());
        assertBalanceMatchesPostings(b.getId());
    }
}
