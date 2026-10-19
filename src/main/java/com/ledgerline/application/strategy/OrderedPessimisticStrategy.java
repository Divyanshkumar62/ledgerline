package com.ledgerline.application.strategy;

import com.ledgerline.application.dto.TransferLegRequest;
import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.application.dto.TransferResponse;
import com.ledgerline.domain.exception.AccountNotFoundException;
import com.ledgerline.domain.exception.CurrencyMismatchException;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.JournalEntry;
import com.ledgerline.domain.repository.AccountRepository;
import com.ledgerline.domain.repository.JournalEntryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Concurrency Strategy A: Ordered Pessimistic Locking.
 * Acquires row-level exclusive locks (SELECT ... FOR UPDATE) strictly in ascending UUID order,
 * eliminating circular wait deadlocks (Coffman condition #4).
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
    public TransferResponse executeTransfer(TransferRequest request) {
        Objects.requireNonNull(request, "Transfer request must not be null");

        // 1. Extract and sort unique account IDs to enforce canonical lock ordering
        List<UUID> sortedAccountIds = request.legs().stream()
                .map(TransferLegRequest::accountId)
                .distinct()
                .sorted()
                .toList();

        // 2. Acquire pessimistic write locks in ascending UUID order
        List<Account> lockedAccountsList = accountRepository.findByIdInOrderByIdAscForUpdate(sortedAccountIds);
        Map<UUID, Account> accountMap = lockedAccountsList.stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));

        // 3. Verify all accounts exist
        for (UUID accountId : sortedAccountIds) {
            if (!accountMap.containsKey(accountId)) {
                throw new AccountNotFoundException(accountId);
            }
        }

        // 4. Validate currency consistency and build domain posting instructions
        List<JournalEntry.PostingInstruction> instructions = new ArrayList<>(request.legs().size());
        String transactionCurrency = request.currency().trim().toUpperCase();

        for (TransferLegRequest leg : request.legs()) {
            Account account = accountMap.get(leg.accountId());
            if (!account.getCurrency().equalsIgnoreCase(transactionCurrency)) {
                throw new CurrencyMismatchException(String.format(
                        "Account %s currency (%s) does not match transfer currency (%s)",
                        account.getId(), account.getCurrency(), transactionCurrency));
            }

            account.applyPosting(leg.direction(), leg.amountMinor(), transactionCurrency);

            instructions.add(new JournalEntry.PostingInstruction(
                    account,
                    leg.direction(),
                    leg.amountMinor()
            ));
        }

        // 5. Create JournalEntry - triggers domain zero-sum verification
        JournalEntry journalEntry = JournalEntry.create(
                request.description(),
                transactionCurrency,
                request.idempotencyKey(),
                instructions
        );

        // 6. Persist atomic journal entry and updated accounts
        accountRepository.saveAll(accountMap.values());
        JournalEntry savedEntry = journalEntryRepository.save(journalEntry);

        return TransferResponse.from(savedEntry);
    }
}
