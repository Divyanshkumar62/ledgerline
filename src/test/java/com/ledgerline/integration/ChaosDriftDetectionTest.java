package com.ledgerline.integration;

import com.ledgerline.application.dto.AuditReport;
import com.ledgerline.application.dto.AuditResult;
import com.ledgerline.application.service.LedgerAuditService;
import com.ledgerline.application.strategy.ConcurrencyStrategyType;
import com.ledgerline.domain.exception.AccountFrozenException;
import com.ledgerline.domain.model.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Week 6 proof: the audit finds drift exactly, freezes the account, alerts once, and never cries wolf. */
class ChaosDriftDetectionTest extends PostgresIntegrationTest {

    @Autowired
    private LedgerAuditService audit;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Horizon in the near future: everything already committed may be folded into a checkpoint. */
    private static Instant everythingCommitted() {
        return Instant.now().plusSeconds(1);
    }

    @Test
    @DisplayName("Clean ledger: zero discrepancies, and the checkpoint advances so the next audit reads only new postings")
    void cleanLedger_checkpointAdvancesIncrementally() {
        Account a = fundedAccount(10_000L);
        Account b = newAccount();
        for (int i = 0; i < 3; i++) {
            transferService.executeTransfer(transfer(a.getId(), b.getId(), 100L));
        }

        assertThat(audit.audit(List.of(a.getId(), b.getId()), everythingCommitted()).discrepancies()).isEmpty();
        long firstCheckpoint = checkpointSeq(a.getId());

        transferService.executeTransfer(transfer(a.getId(), b.getId(), 100L));
        transferService.executeTransfer(transfer(b.getId(), a.getId(), 50L));

        assertThat(audit.audit(List.of(a.getId(), b.getId()), everythingCommitted()).discrepancies()).isEmpty();
        assertThat(checkpointSeq(a.getId())).isGreaterThan(firstCheckpoint);
        assertThat(jdbcTemplate.queryForObject("SELECT verified_balance FROM reconciliation_checkpoints WHERE account_id = ?",
                Long.class, a.getId())).isEqualTo(balanceOf(a.getId())).isEqualTo(10_000L - 400L + 50L);
    }

    @Test
    @DisplayName("Injected drift: exact discrepancy reported, account frozen, one alert event, all strategies refuse it")
    void injectedDrift_isDetectedFrozenAndAlertedOnce() {
        Account a = fundedAccount(10_000L);
        Account b = newAccount();
        transferService.executeTransfer(transfer(a.getId(), b.getId(), 1_000L));
        assertThat(audit.audit(List.of(a.getId(), b.getId()), everythingCommitted()).discrepancies()).isEmpty();

        // Chaos: a bug (or a manual "fix") changes the cached balance without a posting.
        jdbcTemplate.update("UPDATE accounts SET balance = balance + 777 WHERE id = ?", a.getId());

        AuditReport report = audit.audit(List.of(a.getId(), b.getId()), everythingCommitted());

        assertThat(report.discrepancies()).hasSize(1);
        AuditResult drift = report.discrepancies().get(0);
        assertThat(drift.accountId()).isEqualTo(a.getId());
        assertThat(drift.expectedBalance()).isEqualTo(9_000L);
        assertThat(drift.actualBalance()).isEqualTo(9_777L);
        assertThat(drift.discrepancy()).isEqualTo(777L);
        assertThat(drift.newlyFrozen()).isTrue();
        assertThat(accountRepository.findById(a.getId()).orElseThrow().isFrozen()).isTrue();

        assertThat(driftEvents(a.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_events WHERE event_type = 'LedgerDriftDetected' AND event_key = ?",
                String.class, a.getId().toString()))
                .contains("\"delta\":777", "\"expectedBalance\":9000", "\"actualBalance\":9777");

        for (ConcurrencyStrategyType strategy : ConcurrencyStrategyType.values()) {
            assertThatThrownBy(() -> transferService.executeTransfer(transfer(a.getId(), b.getId(), 1L), strategy))
                    .as(strategy.name()).isInstanceOf(AccountFrozenException.class);
            assertThatThrownBy(() -> transferService.executeTransfer(transfer(b.getId(), a.getId(), 1L), strategy))
                    .as(strategy.name() + " (receiving side)").isInstanceOf(AccountFrozenException.class);
        }

        AuditResult again = audit.audit(List.of(a.getId()), everythingCommitted()).discrepancies().get(0);
        assertThat(again.discrepancy()).isEqualTo(777L);
        assertThat(again.newlyFrozen()).isFalse();
        assertThat(driftEvents(a.getId())).as("alert is emitted once, not every round").isEqualTo(1);
    }

    @Test
    @DisplayName("Auditing while 2,000 transfers run concurrently reports zero false discrepancies")
    void auditDuringLoad_reportsNoFalseDrift() throws Exception {
        List<UUID> ids = IntStream.range(0, 4).mapToObj(i -> fundedAccount(1_000_000L).getId()).toList();
        Duration lag = Duration.ofSeconds(2); // longer than any transfer transaction in this test

        CompletableFuture<Outcomes> storm = CompletableFuture.supplyAsync(() -> {
            try {
                return runConcurrently(ConcurrencyStrategyType.ORDERED_PESSIMISTIC, 16, 2_000, i -> {
                    SplittableRandom rnd = new SplittableRandom(i);
                    int from = rnd.nextInt(ids.size());
                    int to = (from + 1 + rnd.nextInt(ids.size() - 1)) % ids.size();
                    return transfer(ids.get(from), ids.get(to), 1 + rnd.nextInt(50));
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        List<AuditResult> falseAlarms = new ArrayList<>();
        int rounds = 0;
        while (!storm.isDone()) {
            falseAlarms.addAll(audit.audit(ids, Instant.now().minus(lag)).discrepancies());
            rounds++;
        }

        assertThat(storm.get().failures()).isEmpty();
        assertThat(rounds).as("audit rounds overlapping the storm").isGreaterThan(1);
        assertThat(falseAlarms).isEmpty();
        assertThat(audit.audit(ids, everythingCommitted()).discrepancies()).isEmpty();
        assertThat(ids.stream().mapToLong(this::balanceOf).sum()).isEqualTo(4_000_000L);
    }

    private long checkpointSeq(UUID accountId) {
        return jdbcTemplate.queryForObject("SELECT last_posting_seq FROM reconciliation_checkpoints WHERE account_id = ?",
                Long.class, accountId);
    }

    private int driftEvents(UUID accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'LedgerDriftDetected' AND event_key = ?",
                Integer.class, accountId.toString());
    }
}
