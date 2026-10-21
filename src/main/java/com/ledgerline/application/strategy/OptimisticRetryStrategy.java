package com.ledgerline.application.strategy;

import com.ledgerline.application.dto.TransferResponse;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.JournalEntry;
import com.ledgerline.domain.repository.AccountRepository;
import com.ledgerline.domain.repository.JournalEntryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Concurrency Strategy B: Optimistic Locking.
 *
 * No locks are held while reading. The @Version column turns each balance write into
 * {@code UPDATE ... WHERE id = ? AND version = ?}; if another transaction committed first,
 * zero rows match and the commit fails with an optimistic-lock conflict.
 *
 * The retry (up to {@link #maxRetries()}, exponential backoff with full jitter) lives in
 * TransferService, OUTSIDE the transaction: a conflict poisons the current transaction, and the
 * retry must also re-run the idempotency claim that shares it.
 */
@Component
public class OptimisticRetryStrategy implements ConcurrencyStrategy {

    static final int MAX_RETRIES = 5;

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;

    public OptimisticRetryStrategy(AccountRepository accountRepository, JournalEntryRepository journalEntryRepository) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

    @Override
    public ConcurrencyStrategyType getType() {
        return ConcurrencyStrategyType.OPTIMISTIC_LOCKING;
    }

    @Override
    public int maxRetries() {
        return MAX_RETRIES;
    }

    @Override
    @Transactional
    public TransferResponse executeTransfer(LedgerCommand command, String reference) {
        List<UUID> ids = TransferPlan.accountIdsInLockOrder(command);
        Map<UUID, Account> accounts = TransferPlan.indexById(ids, accountRepository.findAllById(ids));

        JournalEntry entry = TransferPlan.buildEntry(command, reference, accounts);
        TransferPlan.applyToAccounts(entry); // checks funds against the balance we read; the version check catches it if stale

        // Flush the version-checked balance UPDATEs BEFORE the postings are inserted. Hibernate's default flush
        // order is inserts first: the posting INSERT's foreign-key check takes a KEY SHARE lock on the account row,
        // and a later UPDATE of that same row by this transaction, racing another updater, hit a PostgreSQL
        // internal error ("new multixact has more than one updating member", ~1 in 5 hot-account stress runs).
        // Updating first means this transaction owns the row before inserting postings, like strategies A and C.
        accountRepository.saveAllAndFlush(accounts.values());
        return TransferResponse.from(journalEntryRepository.save(entry));
    }
}
