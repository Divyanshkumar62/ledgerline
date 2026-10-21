package com.ledgerline.application.strategy;

import com.ledgerline.application.dto.TransferResponse;

/**
 * Common interface for concurrency control strategies (Strategy A, B, and C)
 * to benchmark throughput, p99 latency, and rejection rates under contention.
 *
 * Implementations run inside a transaction opened by TransferService (they join it), so the
 * idempotency record and the postings commit or roll back together.
 */
public interface ConcurrencyStrategy {

    ConcurrencyStrategyType getType();

    /**
     * @param reference stored on the journal entry for traceability (client:key), or null
     */
    TransferResponse executeTransfer(LedgerCommand command, String reference);

    /**
     * How many times TransferService may re-run the WHOLE transaction after an optimistic-lock
     * conflict. Only strategies that detect conflicts at commit time need more than 0.
     */
    default int maxRetries() {
        return 0;
    }
}
