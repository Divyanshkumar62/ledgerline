package com.ledgerline.application.strategy;

import com.ledgerline.application.dto.TransferResponse;
import com.ledgerline.domain.exception.InsufficientFundsException;
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
 * Concurrency Strategy C: Atomic Conditional Update.
 *
 * Instead of read-modify-write in Java, each account gets one
 * {@code UPDATE accounts SET balance = balance + :delta WHERE id = :id AND <funds guard>}.
 * The database does the arithmetic and the overdraft check on the latest committed row, so no
 * read is ever stale and nothing needs retrying.
 *
 * Multi-leg atomicity comes from the surrounding transaction: if any leg's guard rejects,
 * we throw and every earlier UPDATE in this transaction rolls back.
 * Deadlock freedom comes from issuing the UPDATEs in canonical lock order.
 */
@Component
public class AtomicConditionalStrategy implements ConcurrencyStrategy {

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;

    public AtomicConditionalStrategy(AccountRepository accountRepository, JournalEntryRepository journalEntryRepository) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

    @Override
    public ConcurrencyStrategyType getType() {
        return ConcurrencyStrategyType.ATOMIC_CONDITIONAL;
    }

    @Override
    @Transactional
    public TransferResponse executeTransfer(LedgerCommand command, String reference) {
        List<UUID> ids = TransferPlan.accountIdsInLockOrder(command);
        // Unlocked read: only used for existence, currency and account type, which never change.
        Map<UUID, Account> accounts = TransferPlan.indexById(ids, accountRepository.findAllById(ids));

        JournalEntry entry = TransferPlan.buildEntry(command, reference, accounts);
        // C bypasses Account.applyPosting, so every balance rule must be repeated here AND in SQL (see ADR-002).
        accounts.values().forEach(Account::assertNotFrozen);

        // UPDATEs before saving the entry, and in lock order: each UPDATE row-locks its account
        // until commit, so the order here IS the lock order.
        TransferPlan.netDeltasInLockOrder(entry).forEach((accountId, delta) -> {
            if (accountRepository.applyBalanceDelta(accountId, delta) == 0) {
                // ponytail: 0 rows means "funds guard OR frozen guard rejected"; a freeze that lands between the
                // snapshot check above and this UPDATE is reported as insufficient funds. Re-read to disambiguate if needed.
                throw new InsufficientFundsException(accountId, accounts.get(accountId).getBalance(), delta);
            }
        });

        return TransferResponse.from(journalEntryRepository.save(entry));
    }
}
