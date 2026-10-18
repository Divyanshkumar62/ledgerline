package com.ledgerline.domain.model;

/**
 * Standard double-entry bookkeeping posting direction.
 */
public enum PostingDirection {
    DEBIT,
    CREDIT;

    public PostingDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
