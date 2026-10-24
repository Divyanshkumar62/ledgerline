package com.ledgerline.application.service;

import com.ledgerline.application.dto.AuditReport;
import com.ledgerline.application.dto.AuditResult;
import com.ledgerline.domain.event.LedgerDriftDetected;
import com.ledgerline.domain.exception.AccountNotFoundException;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.PostingWindow;
import com.ledgerline.domain.model.ReconciliationCheckpoint;
import com.ledgerline.domain.repository.AccountRepository;
import com.ledgerline.domain.repository.OutboxRepository;
import com.ledgerline.domain.repository.ReconciliationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Self-audit (ADR-008): verifies {@code accounts.balance == checkpoint.verified_balance + Σ postings since checkpoint}.
 *
 * Each account is audited in its own REPEATABLE READ transaction, so the balance and the postings are read
 * from ONE snapshot. A transfer that is committed is fully visible (balance and postings); one that is not is
 * fully invisible. Without that, a transfer committing between the two reads would look like drift.
 *
 * On drift: freeze the account and emit {@link LedgerDriftDetected} through the outbox, in the same transaction.
 */
@Service
public class LedgerAuditService {

    private static final Logger log = LoggerFactory.getLogger(LedgerAuditService.class);

    private final AccountRepository accountRepository;
    private final ReconciliationRepository reconciliationRepository;
    private final OutboxRepository outboxRepository;
    private final TransactionTemplate snapshotTransaction;
    private final MeterRegistry meterRegistry;

    public LedgerAuditService(AccountRepository accountRepository, ReconciliationRepository reconciliationRepository,
                              OutboxRepository outboxRepository, PlatformTransactionManager transactionManager,
                              MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.outboxRepository = outboxRepository;
        this.snapshotTransaction = new TransactionTemplate(transactionManager);
        this.snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.meterRegistry = meterRegistry;
    }

    public AuditReport auditAll(Instant horizon) {
        return audit(reconciliationRepository.allAccountIds(), horizon);
    }

    /**
     * @param horizon postings created before this instant may be folded into a new checkpoint.
     *                It must trail "now" by more than the longest transfer transaction can stay open
     *                (see ADR-008); verification itself does not depend on it.
     */
    public AuditReport audit(Collection<UUID> accountIds, Instant horizon) {
        List<AuditResult> discrepancies = new ArrayList<>();
        List<UUID> skipped = new ArrayList<>();
        for (UUID id : accountIds) {
            try {
                AuditResult result = auditAccount(id, horizon);
                meterRegistry.counter("ledger.audit.accounts.checked").increment();
                if (result.drifted()) {
                    discrepancies.add(result);
                }
                if (result.newlyFrozen()) {
                    meterRegistry.counter("ledger.audit.drift.detected").increment();
                }
            } catch (DataAccessException e) {
                log.warn("Audit of account {} skipped this round: {}", id, e.toString());
                skipped.add(id);
            }
        }
        return new AuditReport(accountIds.size() - skipped.size(), discrepancies, skipped);
    }

    public AuditResult auditAccount(UUID accountId, Instant horizon) {
        return snapshotTransaction.execute(status -> {
            Account account = accountRepository.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
            ReconciliationCheckpoint checkpoint = reconciliationRepository.findCheckpoint(accountId)
                    .orElse(ReconciliationCheckpoint.origin(accountId));
            PostingWindow window = reconciliationRepository.postingsAfter(
                    accountId, checkpoint.lastPostingSeq(), account.getType().getNormalBalance(), horizon);

            long expected = Math.addExact(checkpoint.verifiedBalance(), window.deltaSinceCheckpoint());
            long actual = account.getBalance();

            if (actual != expected) {
                boolean freezeNow = !account.isFrozen(); // alert once, not on every audit round
                if (freezeNow) {
                    reconciliationRepository.freeze(accountId);
                    outboxRepository.append(new LedgerDriftDetected(accountId, expected, actual, actual - expected, Instant.now()));
                }
                log.error("LEDGER DRIFT account={} expected={} actual={} delta={} frozen={}",
                        accountId, expected, actual, actual - expected, true);
                return new AuditResult(accountId, expected, actual, freezeNow); // checkpoint is NOT advanced
            }

            if (window.horizonSeq() != null) {
                reconciliationRepository.saveCheckpoint(new ReconciliationCheckpoint(accountId,
                        Math.addExact(checkpoint.verifiedBalance(), window.deltaToHorizon()), window.horizonSeq(), Instant.now()));
            }
            return new AuditResult(accountId, expected, actual, false);
        });
    }
}
