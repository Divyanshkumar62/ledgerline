package com.ledgerline.domain.exception;

public class ImbalancedJournalEntryException extends RuntimeException {
    private final long totalDebits;
    private final long totalCredits;
    private final long discrepancy;

    public ImbalancedJournalEntryException(long totalDebits, long totalCredits) {
        this(totalDebits, totalCredits, null);
    }

    public ImbalancedJournalEntryException(long totalDebits, long totalCredits, String currency) {
        super(String.format("Imbalanced journal entry%s: Total debits (%d) do not equal total credits (%d). Discrepancy: %d",
                currency == null ? "" : " in " + currency, totalDebits, totalCredits, totalDebits - totalCredits));
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
