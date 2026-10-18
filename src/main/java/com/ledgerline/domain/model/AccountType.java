package com.ledgerline.domain.model;

/**
 * Fundamental financial account types with natural balance sides.
 */
public enum AccountType {
    ASSET(PostingDirection.DEBIT),
    LIABILITY(PostingDirection.CREDIT),
    EQUITY(PostingDirection.CREDIT),
    REVENUE(PostingDirection.CREDIT),
    EXPENSE(PostingDirection.DEBIT);

    private final PostingDirection normalBalance;

    AccountType(PostingDirection normalBalance) {
        this.normalBalance = normalBalance;
    }

    public PostingDirection getNormalBalance() {
        return normalBalance;
    }

    public boolean isNormalDebit() {
        return normalBalance == PostingDirection.DEBIT;
    }

    /**
     * Calculates the net signed balance change for this account type.
     * When the posting direction matches the normal balance side, the balance increases (+amount).
     * When opposite, the balance decreases (-amount).
     */
    public long calculateSignedDelta(PostingDirection direction, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must be positive minor units");
        }
        return (direction == normalBalance) ? amount : -amount;
    }
}
