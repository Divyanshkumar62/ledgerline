package com.ledgerline.application.strategy;

/**
 * Concurrency control models defined in feature specification Section 3.1.
 */
public enum ConcurrencyStrategyType {
    ORDERED_PESSIMISTIC,
    OPTIMISTIC_LOCKING,
    ATOMIC_CONDITIONAL
}
