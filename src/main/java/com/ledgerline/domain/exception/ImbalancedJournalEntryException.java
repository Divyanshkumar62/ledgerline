package com.ledgerline.domain.exception;

public class ImbalancedJournalEntryException extends RuntimeException {
    private final long totalDebits;
    private final long totalCredits;
    private final long discrepancy;

    public ImbalancedJournalEntryException(long totalDebits, long totalCredits) {
        super(String.format("Imbalanced journal entry: Total debits (%d) do not equal total credits (%d). Discrepancy: %d",
                totalDebits, totalCredits, totalDebits - totalCredits));
        this.totalDebits = totalDebits;
        this.totalCredits = totalCredits;
        this.discrepancy = totalDebits - totalCredits;
    }

    public long getTotalDebits() {
        return totalDebits;
    }

    public long getTotalCredits() {
        return totalCredits;
    }

    public long getDiscrepancy() {
        return discrepancy;
    }
}
