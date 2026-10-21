package com.ledgerline.domain.exception;

import java.util.UUID;

/** Unfreeze refused: the account still fails its audit. Repair it first (ADR-013). */
public class AccountStillDriftedException extends RuntimeException {
    private final UUID accountId;
    private final long discrepancy;

    public AccountStillDriftedException(UUID accountId, long discrepancy) {
        super("Account " + accountId + " still differs from its postings by " + discrepancy
                + "; repair it before unfreezing");
        this.accountId = accountId;
        this.discrepancy = discrepancy;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getDiscrepancy() {
        return discrepancy;
    }
}
