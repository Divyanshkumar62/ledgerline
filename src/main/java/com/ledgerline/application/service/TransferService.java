package com.ledgerline.application.service;

import com.ledgerline.application.dto.FxTransferOutcome;
import com.ledgerline.application.dto.FxTransferRequest;
import com.ledgerline.application.dto.FxTransferResponse;
import com.ledgerline.application.dto.TransferOutcome;
import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.application.dto.TransferResponse;
import com.ledgerline.application.strategy.ConcurrencyStrategy;
import com.ledgerline.application.strategy.ConcurrencyStrategyType;
import com.ledgerline.application.strategy.LedgerCommand;
import com.ledgerline.domain.event.TransferCompleted;
import com.ledgerline.domain.exception.AccountNotFoundException;
import com.ledgerline.domain.exception.IdempotencyKeyReusedException;
import com.ledgerline.domain.exception.InvalidRequestException;
import com.ledgerline.domain.model.AccountType;
import com.ledgerline.domain.model.FxConversion;
import com.ledgerline.domain.model.IdempotencyRecord;
import com.ledgerline.domain.model.PostingDirection;
import com.ledgerline.domain.repository.AccountRepository;
import com.ledgerline.domain.repository.FxConversionRepository;
import com.ledgerline.domain.repository.IdempotencyRepository;
import com.ledgerline.domain.repository.JournalEntryRepository;
import com.ledgerline.domain.repository.OutboxRepository;
import com.ledgerline.domain.repository.SystemAccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Entry point for every transfer. Owns the transaction boundary:
 *
 * <pre>
 * retry loop (only for strategies with maxRetries > 0)          ← outside the transaction
 *   └─ transaction
 *        ├─ check the caller owns every account it names (404 otherwise)
 *        ├─ claim idempotency key (blocks on an in-flight duplicate)
 *        ├─ strategy.executeTransfer   (joins this transaction)
 *        ├─ link key → journal entry
 *        └─ append TransferCompleted to the outbox
 *      COMMIT → postings, balances, idempotency record and event become visible together
 * </pre>
 *
 * Not annotated @Transactional: a retry must start a NEW transaction, and an optimistic-lock
 * conflict marks the current one rollback-only.
 */
@Service
public class TransferService {

    /** Chosen from Week 3 benchmark data, see ADR-002. */
    public static final ConcurrencyStrategyType DEFAULT_STRATEGY = ConcurrencyStrategyType.ORDERED_PESSIMISTIC;

    private static final long BASE_BACKOFF_MS = 5;
    private static final long MAX_BACKOFF_MS = 100;

    private final Map<ConcurrencyStrategyType, ConcurrencyStrategy> strategies = new EnumMap<>(ConcurrencyStrategyType.class);
    private final IdempotencyRepository idempotencyRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final OutboxRepository outboxRepository;
    private final AccountRepository accountRepository;
    private final SystemAccountRepository systemAccountRepository;
    private final FxConversionRepository fxConversionRepository;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public TransferService(List<ConcurrencyStrategy> strategyList,
                           IdempotencyRepository idempotencyRepository,
                           JournalEntryRepository journalEntryRepository,
                           OutboxRepository outboxRepository,
                           AccountRepository accountRepository,
                           SystemAccountRepository systemAccountRepository,
                           FxConversionRepository fxConversionRepository,
                           PlatformTransactionManager transactionManager,
                           MeterRegistry meterRegistry) {
        strategyList.forEach(s -> strategies.put(s.getType(), s));
        this.idempotencyRepository = idempotencyRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.outboxRepository = outboxRepository;
        this.accountRepository = accountRepository;
        this.systemAccountRepository = systemAccountRepository;
        this.fxConversionRepository = fxConversionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
    }

    /**
     * The public API path: at most one ledger effect per (clientId, idempotencyKey), and only between
     * accounts the client owns. Same key + same payload → the original response is replayed.
     * Same key + different payload → {@link IdempotencyKeyReusedException}.
     */
    public TransferOutcome executeIdempotent(String clientId, String idempotencyKey, TransferRequest request) {
        Objects.requireNonNull(request, "Transfer request must not be null");
        LedgerCommand command = LedgerCommand.of(request);
        return idempotent(clientId, idempotencyKey, RequestFingerprint.of(request), command.accountIds(),
                (strategy, reference) -> transferAndPublish(strategy, command, reference));
    }

    /**
     * Cross-currency transfer (ADR-012). Four legs, balanced per currency, through the ledger's FX clearing
     * accounts: the source currency moves client → clearing, the target currency moves clearing → client.
     */
    public FxTransferOutcome executeFxIdempotent(String clientId, String idempotencyKey, FxTransferRequest request) {
        Objects.requireNonNull(request, "FX transfer request must not be null");
        TransferOutcome outcome = idempotent(clientId, idempotencyKey, RequestFingerprint.of(request),
                List.of(request.fromAccountId(), request.toAccountId()),
                (strategy, reference) -> fxTransfer(strategy, request, reference));
        FxConversion fx = fxConversionRepository.findByJournalEntryId(outcome.response().journalEntryId())
                .orElseThrow(() -> new IllegalStateException("FX entry without its conversion record"));
        return new FxTransferOutcome(FxTransferResponse.of(outcome.response(), fx), outcome.replayed());
    }

    /** Internal path without idempotency or ownership checks (funding in tests, benchmarks, admin tooling). */
    public TransferResponse executeTransfer(TransferRequest request) {
        return executeTransfer(request, DEFAULT_STRATEGY);
    }

    public TransferResponse executeTransfer(TransferRequest request, ConcurrencyStrategyType strategyType) {
        Objects.requireNonNull(request, "Transfer request must not be null");
        ConcurrencyStrategy strategy = strategy(strategyType);
        LedgerCommand command = LedgerCommand.of(request);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            TransferResponse response = inTransactionWithRetry(strategy, () -> transferAndPublish(strategy, command, null));
            stopTimer(sample, strategy, "committed");
            return response;
        } catch (RuntimeException e) {
            stopTimer(sample, strategy, outcomeOf(e));
            throw e;
        }
    }

    private TransferOutcome idempotent(String clientId, String idempotencyKey, String requestHash, Collection<UUID> clientAccounts,
                                       BiFunction<ConcurrencyStrategy, String, TransferResponse> work) {
        ConcurrencyStrategy strategy = strategy(DEFAULT_STRATEGY);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            TransferOutcome outcome = inTransactionWithRetry(strategy, () -> {
                // Before the claim, so a request naming someone else's account never consumes a key.
                assertOwnedBy(clientId, clientAccounts);
                if (!idempotencyRepository.claim(clientId, idempotencyKey, requestHash)) {
                    return replay(clientId, idempotencyKey, requestHash);
                }
                TransferResponse response = work.apply(strategy, clientId + ":" + idempotencyKey);
                idempotencyRepository.complete(clientId, idempotencyKey, response.journalEntryId());
                return new TransferOutcome(response, false);
            });
            // Counted after the transaction: a rolled-back attempt must not count as a hit or a miss.
            meterRegistry.counter(outcome.replayed() ? "ledger.idempotency.hits" : "ledger.idempotency.misses").increment();
            stopTimer(sample, strategy, outcome.replayed() ? "replayed" : "committed");
            return outcome;
        } catch (RuntimeException e) {
            if (e instanceof IdempotencyKeyReusedException) {
                meterRegistry.counter("ledger.idempotency.mismatches").increment();
            }
            stopTimer(sample, strategy, outcomeOf(e));
            throw e;
        }
    }

    /**
     * Ownership (ADR-011). Not owned and does not exist look the same to the caller (404), so ids of other
     * clients' accounts cannot be probed. A scalar query: it must not load Account entities into the
     * persistence context before the strategy locks them, or the strategy would see their pre-lock state.
     */
    private void assertOwnedBy(String clientId, Collection<UUID> accountIds) {
        Set<UUID> owned = new HashSet<>(accountRepository.findIdsOwnedBy(accountIds, clientId));
        for (UUID id : accountIds) {
            if (!owned.contains(id)) {
                throw new AccountNotFoundException(id);
            }
        }
    }

    private TransferResponse fxTransfer(ConcurrencyStrategy strategy, FxTransferRequest request, String reference) {
        // Scalar reads only (see assertOwnedBy): currencies never change, balances are read by the strategy under lock.
        String source = accountRepository.findCurrencyById(request.fromAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.fromAccountId()));
        String target = accountRepository.findCurrencyById(request.toAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.toAccountId()));
        if (source.equals(target)) {
            throw new InvalidRequestException("Both accounts are in " + source + "; use /api/v1/transfers for a same-currency transfer");
        }
        long targetAmount = FxMath.convert(request.sourceAmountMinor(), source, target, request.rate());
        UUID sourceClearing = systemAccountRepository.findOrCreate(SystemAccountRepository.FX_CLEARING, source, AccountType.ASSET);
        UUID targetClearing = systemAccountRepository.findOrCreate(SystemAccountRepository.FX_CLEARING, target, AccountType.ASSET);

        LedgerCommand command = new LedgerCommand(request.description(), null, List.of(
                new LedgerCommand.Leg(request.fromAccountId(), PostingDirection.CREDIT, request.sourceAmountMinor()),
                new LedgerCommand.Leg(sourceClearing, PostingDirection.DEBIT, request.sourceAmountMinor()),
                new LedgerCommand.Leg(targetClearing, PostingDirection.CREDIT, targetAmount),
                new LedgerCommand.Leg(request.toAccountId(), PostingDirection.DEBIT, targetAmount)));

        TransferResponse response = transferAndPublish(strategy, command, reference);
        fxConversionRepository.record(new FxConversion(response.journalEntryId(), source, target,
                request.sourceAmountMinor(), targetAmount, request.rate(), FxMath.ROUNDING));
        return response;
    }

    /** Must run inside the transaction: the event exists if and only if the transfer committed. */
    private TransferResponse transferAndPublish(ConcurrencyStrategy strategy, LedgerCommand command, String reference) {
        TransferResponse response = strategy.executeTransfer(command, reference);
        outboxRepository.append(new TransferCompleted(response.journalEntryId(), response.description(), response.currency(),
                response.legs().stream()
                        .map(l -> new TransferCompleted.Leg(l.accountId(), l.direction(), l.amountMinor(), l.currency()))
                        .toList(),
                response.createdAt()));
        return response;
    }

    private TransferOutcome replay(String clientId, String idempotencyKey, String requestHash) {
        IdempotencyRecord existing = idempotencyRepository.find(clientId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Claim conflicted but no committed record exists"));
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(idempotencyKey);
        }
        // Journal entries are immutable, so rebuilding the response yields exactly the original body.
        TransferResponse original = journalEntryRepository.findById(existing.journalEntryId())
                .map(TransferResponse::from)
                .orElseThrow(() -> new IllegalStateException("Idempotency record points to a missing journal entry"));
        return new TransferOutcome(original, true);
    }

    /**
     * Low-cardinality outcome tag. "rejected" = a business rule said no (insufficient funds, frozen,
     * imbalanced, key reused...); "conflict" = contention, safe to retry; "error" = anything unexpected.
     */
    private static String outcomeOf(RuntimeException e) {
        if (e instanceof ConcurrencyFailureException) {
            return "conflict";
        }
        return e.getClass().getPackageName().equals(AccountNotFoundException.class.getPackageName()) ? "rejected" : "error";
    }

    private void stopTimer(Timer.Sample sample, ConcurrencyStrategy strategy, String outcome) {
        sample.stop(Timer.builder("ledger.transfer.duration")
                .description("Transfer time inside the service, including lock waits and retries")
                .tag("strategy", strategy.getType().name())
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry));
    }

    private ConcurrencyStrategy strategy(ConcurrencyStrategyType type) {
        ConcurrencyStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("No concurrency strategy registered for: " + type);
        }
        return strategy;
    }

    private <T> T inTransactionWithRetry(ConcurrencyStrategy strategy, Supplier<T> work) {
        for (int attempt = 0; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> work.get());
            } catch (OptimisticLockingFailureException conflict) {
                if (attempt >= strategy.maxRetries()) {
                    throw conflict;
                }
                meterRegistry.counter("ledger.concurrency.retries", "strategy", strategy.getType().name()).increment();
                sleepWithFullJitter(attempt, conflict);
            }
        }
    }

    /**
     * Full jitter: sleep a uniformly random time in [0, min(cap, base * 2^attempt)].
     * Spreading retries randomly stops the losers of one conflict from colliding again in lockstep.
     */
    private static void sleepWithFullJitter(int attempt, OptimisticLockingFailureException conflict) {
        long ceiling = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << attempt);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(ceiling + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw conflict;
        }
    }
}
