package com.ledgerline.application.service;

import com.ledgerline.application.dto.TransferLegRequest;
import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.application.dto.TransferResponse;
import com.ledgerline.domain.exception.AccountNotFoundException;
import com.ledgerline.domain.exception.CurrencyMismatchException;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.JournalEntry;
import com.ledgerline.domain.repository.AccountRepository;
import com.ledgerline.domain.repository.JournalEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates multi-leg financial transfers using Concurrency Strategy A:
 * Ordered Pessimistic Locking.
 * 
 * Invariant Guarantees:
 * 1. Deadlock-free: locks accounts strictly in ascending UUID order.
 * 2. Zero-sum: verifies sum(debits) == sum(credits) in domain model and database trigger.
 * 3. Atomicity: entire transfer commits or rolls back without partial mutations.
 */
@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;

    public TransferService(AccountRepository accountRepository, JournalEntryRepository journalEntryRepository) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

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

            // Apply posting to cached balance projection (enforcing non-negative balance rule)
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
