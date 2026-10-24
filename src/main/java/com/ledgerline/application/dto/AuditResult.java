package com.ledgerline.application.dto;

import java.util.UUID;

/**
 * Outcome of auditing one account.
 *
 * @param expectedBalance checkpoint balance + signed sum of postings since the checkpoint
 * @param actualBalance   the cached accounts.balance
 * @param newlyFrozen     true if this audit froze the account (and emitted LedgerDriftDetected)
 */
public record AuditResult(UUID accountId, long expectedBalance, long actualBalance, boolean newlyFrozen) {

    public long discrepancy() {
        return actualBalance - expectedBalance;
    }

    public boolean drifted() {
        return discrepancy() != 0;
    }
}
