package com.ledgerline.application.service;

import com.ledgerline.application.dto.AdminActionResult;
import com.ledgerline.application.dto.AuditResult;
import com.ledgerline.domain.event.AccountAdminActionTaken;
import com.ledgerline.domain.event.TransferCompleted;
import com.ledgerline.domain.exception.AccountNotFoundException;
import com.ledgerline.domain.exception.AccountStillDriftedException;
import com.ledgerline.domain.exception.InvalidRequestException;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.AccountType;
import com.ledgerline.domain.model.JournalEntry;
import com.ledgerline.domain.model.PostingDirection;
import com.ledgerline.domain.repository.AccountRepository;
import com.ledgerline.domain.repository.AdminActionRepository;
import com.ledgerline.domain.repository.JournalEntryRepository;
import com.ledgerline.domain.repository.OutboxRepository;
import com.ledgerline.domain.repository.PostingRepository;
import com.ledgerline.domain.repository.SystemAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin interventions on an account (ADR-013): freeze, repair, unfreeze. Every action is locked like a
 * transfer, recorded in the append-only {@code account_admin_actions} trail with who and why, and
 * published as {@link AccountAdminActionTaken} through the outbox, all in one transaction.
 *
 * <p>Drift means the cached balance and the postings disagree. Which side is right decides the repair:
 * <ul>
 *   <li>{@link #rebuildBalance}: the postings are right (e.g. someone edited the balance by hand),
 *       so the cache is rebuilt from them. No money moves.</li>
 *   <li>{@link #bookMissingPosting}: the cached balance is right (e.g. a posting was lost), so the
 *       missing amount is booked as a real journal entry against the ledger's adjustment account.</li>
 * </ul>
 * {@link #unfreeze} is refused until the account audits clean.
 */
@Service
public class AccountAdminService {

    private final AccountRepository accountRepository;
    private final PostingRepository postingRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final SystemAccountRepository systemAccountRepository;
    private final AdminActionRepository adminActionRepository;
    private final OutboxRepository outboxRepository;
    private final LedgerAuditService auditService;
    private final TransactionTemplate tx;

    public AccountAdminService(AccountRepository accountRepository, PostingRepository postingRepository,
                               JournalEntryRepository journalEntryRepository, SystemAccountRepository systemAccountRepository,
                               AdminActionRepository adminActionRepository, OutboxRepository outboxRepository,
                               LedgerAuditService auditService, PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.postingRepository = postingRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.systemAccountRepository = systemAccountRepository;
        this.adminActionRepository = adminActionRepository;
        this.outboxRepository = outboxRepository;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Stop all movement on an account, e.g. on suspected fraud. Idempotent. */
    public AdminActionResult freeze(UUID accountId, String reason, String actor) {
        return tx.execute(status -> {
            Account account = lock(List.of(accountId)).get(accountId);
            account.freeze();
            return recorded(account, "FREEZE", reason, actor, account.getBalance(), null);
        });
    }

    /** The postings are the truth: rebuild the cached balance from them. */
    public AdminActionResult rebuildBalance(UUID accountId, String reason, String actor) {
        return tx.execute(status -> {
            Account account = lock(List.of(accountId)).get(accountId);
            long before = account.getBalance();
            long fromPostings = postingRepository.computeProjectedBalance(accountId, account.getType().getNormalBalance());
            account.resetCachedBalance(fromPostings);
            return recorded(account, "REBUILD_BALANCE", reason, actor, before, null);
        });
    }

    /**
     * The cached balance is the truth: book the missing amount as a journal entry against the ledger's
     * adjustment account (EQUITY) in the same currency. Only the adjustment account's cached balance
     * moves, because this account's cache already includes the amount; afterwards its postings sum to it.
     * The amount is computed by the ledger (balance − postings), never typed in by the admin.
     */
    public AdminActionResult bookMissingPosting(UUID accountId, String reason, String actor) {
        return tx.execute(status -> {
            String currency = accountRepository.findCurrencyById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
            UUID adjustmentId = systemAccountRepository.findOrCreate(SystemAccountRepository.ADJUSTMENT, currency, AccountType.EQUITY);
            Map<UUID, Account> locked = lock(List.of(accountId, adjustmentId));
            Account account = locked.get(accountId);
            Account adjustment = locked.get(adjustmentId);

            long fromPostings = postingRepository.computeProjectedBalance(accountId, account.getType().getNormalBalance());
            long missing = Math.subtractExact(account.getBalance(), fromPostings);
            if (missing == 0) {
                throw new InvalidRequestException("Account " + accountId + " has no missing postings to book");
            }
            PostingDirection normal = account.getType().getNormalBalance();
            PostingDirection direction = missing > 0 ? normal : normal.opposite();
            long amount = Math.abs(missing);

            JournalEntry entry = JournalEntry.create("Adjustment: " + reason, currency, "admin:" + actor + ":" + UUID.randomUUID(),
                    List.of(new JournalEntry.PostingInstruction(account, direction, amount),
                            new JournalEntry.PostingInstruction(adjustment, direction.opposite(), amount)));
            adjustment.applyPosting(direction.opposite(), amount, currency); // account's cache already holds this amount
            JournalEntry saved = journalEntryRepository.save(entry);

            outboxRepository.append(new TransferCompleted(saved.getId(), saved.getDescription(), saved.getCurrency(),
                    saved.getPostings().stream()
                            .map(p -> new TransferCompleted.Leg(p.getAccount().getId(), p.getDirection(), p.getAmount(), p.getCurrency()))
                            .toList(),
                    saved.getCreatedAt()));
            return recorded(account, "BOOK_MISSING_POSTING", reason, actor, account.getBalance(), saved.getId());
        });
    }

    /** Refused (409) unless the account now audits clean: an account that is still wrong stays frozen. */
    public AdminActionResult unfreeze(UUID accountId, String reason, String actor) {
        Account current = accountRepository.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!current.isFrozen()) {
            throw new InvalidRequestException("Account " + accountId + " is not frozen");
        }
        AuditResult audit = auditService.auditAccount(accountId, Instant.now());
        if (audit.drifted()) {
            throw new AccountStillDriftedException(accountId, audit.discrepancy());
        }
        // No transfer can touch a frozen account, so nothing can drift between the audit and this unfreeze.
        return tx.execute(status -> {
            Account account = lock(List.of(accountId)).get(accountId);
            account.unfreeze();
            return recorded(account, "UNFREEZE", reason, actor, account.getBalance(), null);
        });
    }

    public AuditResult audit(UUID accountId) {
        return auditService.auditAccount(accountId, Instant.now());
    }

    private Map<UUID, Account> lock(List<UUID> ids) {
        Map<UUID, Account> locked = accountRepository.findByIdInOrderByIdAscForUpdate(ids).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        ids.forEach(id -> {
            if (!locked.containsKey(id)) {
                throw new AccountNotFoundException(id);
            }
        });
        return locked;
    }

    private AdminActionResult recorded(Account account, String action, String reason, String actor, long before, UUID journalEntryId) {
        accountRepository.save(account);
        long after = account.getBalance();
        adminActionRepository.record(account.getId(), action, reason, actor, before, after, journalEntryId);
        outboxRepository.append(new AccountAdminActionTaken(account.getId(), action, reason, actor, before, after,
                journalEntryId, Instant.now()));
        return new AdminActionResult(account.getId(), action, before, after, account.isFrozen(), journalEntryId);
    }
}
