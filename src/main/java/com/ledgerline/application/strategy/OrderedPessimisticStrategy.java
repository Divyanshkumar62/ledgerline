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
 * Concurrency Strategy A: Ordered Pessimistic Locking.
 * Acquires row-level exclusive locks (SELECT ... FOR UPDATE) in one statement ordered by id,
 * so every transaction locks overlapping accounts in the same order. That removes circular
 * wait (Coffman condition #4), so deadlocks cannot form.
 */
@Component
public class OrderedPessimisticStrategy implements ConcurrencyStrategy {

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;

    public OrderedPessimisticStrategy(AccountRepository accountRepository, JournalEntryRepository journalEntryRepository) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

    @Override
    public ConcurrencyStrategyType getType() {
        return ConcurrencyStrategyType.ORDERED_PESSIMISTIC;
    }

    @Override
    @Transactional
    public TransferResponse executeTransfer(LedgerCommand command, String reference) {
        List<UUID> ids = TransferPlan.accountIdsInLockOrder(command);

        // Blocks until every row is ours; PostgreSQL locks them in its ORDER BY id order.
        Map<UUID, Account> accounts = TransferPlan.indexById(ids, accountRepository.findByIdInOrderByIdAscForUpdate(ids));

        JournalEntry entry = TransferPlan.buildEntry(command, reference, accounts);
        TransferPlan.applyToAccounts(entry);

        accountRepository.saveAll(accounts.values());
        return TransferResponse.from(journalEntryRepository.save(entry));
    }
}
