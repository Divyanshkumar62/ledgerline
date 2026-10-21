package com.ledgerline.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The audit found an account whose cached balance disagrees with its postings; the account has
 * been frozen. Published to {@code ledger.accounts.v1}, keyed by account.
 */
public record LedgerDriftDetected(UUID accountId, long expectedBalance, long actualBalance, long delta,
                                  Instant detectedAt) implements LedgerEvent {

    @Override
    public String type() {
        return "LedgerDriftDetected";
    }

    @Override
    public String aggregateKey() {
        return accountId.toString();
    }

    @Override
    public UUID journalEntryId() {
        return null;
    }
}
