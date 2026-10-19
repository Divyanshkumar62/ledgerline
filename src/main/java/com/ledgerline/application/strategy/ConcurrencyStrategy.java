package com.ledgerline.application.strategy;

import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.application.dto.TransferResponse;

/**
 * Common interface for concurrency control strategies (Strategy A, B, and C)
 * to benchmark throughput, p99 latency, and rejection rates under contention.
 */
public interface ConcurrencyStrategy {

    ConcurrencyStrategyType getType();

    TransferResponse executeTransfer(TransferRequest request);
}
