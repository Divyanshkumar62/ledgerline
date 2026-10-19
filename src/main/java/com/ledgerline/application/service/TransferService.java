package com.ledgerline.application.service;

import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.application.dto.TransferResponse;
import com.ledgerline.application.strategy.ConcurrencyStrategy;
import com.ledgerline.application.strategy.ConcurrencyStrategyType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * High-level Transfer Service delegating to pluggable Concurrency Strategies
 * (Strategy A: Ordered Pessimistic, Strategy B: Optimistic Jitter, Strategy C: Atomic Conditional).
 * 
 * Invariant Guarantees:
 * 1. Pluggable execution: allows benchmarking across locking models (ADR-002).
 * 2. Zero-sum: verifies sum(debits) == sum(credits) in domain model and database trigger.
 * 3. Atomicity: entire transfer commits or rolls back without partial mutations.
 */
@Service
public class TransferService {

    private final Map<ConcurrencyStrategyType, ConcurrencyStrategy> strategies;
    private final ConcurrencyStrategy defaultStrategy;

    public TransferService(List<ConcurrencyStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(ConcurrencyStrategy::getType, Function.identity()));
        this.defaultStrategy = strategies.getOrDefault(
                ConcurrencyStrategyType.ORDERED_PESSIMISTIC,
                strategyList.isEmpty() ? null : strategyList.get(0)
        );
    }

    @Transactional
    public TransferResponse executeTransfer(TransferRequest request) {
        return executeTransfer(request, ConcurrencyStrategyType.ORDERED_PESSIMISTIC);
    }

    @Transactional
    public TransferResponse executeTransfer(TransferRequest request, ConcurrencyStrategyType strategyType) {
        Objects.requireNonNull(request, "Transfer request must not be null");
        ConcurrencyStrategy strategy = strategies.get(strategyType);
        if (strategy == null) {
            throw new IllegalArgumentException("No concurrency strategy registered for: " + strategyType);
        }
        return strategy.executeTransfer(request);
    }
}
