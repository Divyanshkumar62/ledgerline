package com.ledgerline.application.service;

import com.ledgerline.application.dto.AccountResponse;
import com.ledgerline.application.dto.AccountStatementResponse;
import com.ledgerline.application.dto.CreateAccountRequest;
import com.ledgerline.domain.exception.AccountNotFoundException;
import com.ledgerline.domain.exception.InvalidRequestException;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.ApiClient;
import com.ledgerline.domain.model.StatementLine;
import com.ledgerline.domain.repository.AccountRepository;
import com.ledgerline.domain.repository.PostingRepository;
import com.ledgerline.domain.repository.StatementRepository;
import com.ledgerline.domain.repository.StripeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AccountService {

    static final int MAX_STATEMENT_PAGE = 200;

    private final AccountRepository accountRepository;
    private final PostingRepository postingRepository;
    private final StatementRepository statementRepository;
    private final StripeRepository stripeRepository;

    public AccountService(AccountRepository accountRepository, PostingRepository postingRepository,
                          StatementRepository statementRepository, StripeRepository stripeRepository) {
        this.accountRepository = accountRepository;
        this.postingRepository = postingRepository;
        this.statementRepository = statementRepository;
        this.stripeRepository = stripeRepository;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request, ApiClient owner) {
        String currency = request.currency().trim().toUpperCase(Locale.ROOT);
        try {
            FxMath.minorDigits(Currency.getInstance(currency).getCurrencyCode()); // real ISO 4217 code with a minor unit
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Unknown ISO 4217 currency: " + currency);
        }
        if (request.stripes() == 0) {
            Account account = new Account(request.type(), currency, request.disallowNegativeBalance(), owner.clientId());
            return AccountResponse.from(accountRepository.save(account));
        }
        if (request.disallowNegativeBalance()) {
            throw new InvalidRequestException("A striped account is receive-only, so it cannot have disallowNegativeBalance");
        }
        // Parent and stripes in one transaction: a parent without all of its stripes is never visible.
        Account parent = accountRepository.save(Account.striped(request.type(), currency, owner.clientId(), request.stripes()));
        for (int i = 0; i < request.stripes(); i++) {
            accountRepository.save(Account.stripeOf(parent, i));
        }
        return AccountResponse.from(parent, 0);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID id, ApiClient caller) {
        Account account = visibleAccount(id, caller);
        return AccountResponse.from(account, balanceOf(account));
    }

    /** A striped parent holds nothing itself: its balance is the sum of its stripes, read in one statement. */
    private long balanceOf(Account account) {
        return account.isStriped() ? stripeRepository.sumStripeBalances(account.getId()) : account.getBalance();
    }

    /**
     * Account statement, newest first, keyset-paginated on posting seq. REPEATABLE READ: the balance and
     * the postings come from one snapshot, so {@code balanceAfter} is consistent even while transfers run.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccountStatementResponse statement(UUID id, ApiClient caller, Long before, int limit) {
        if (limit < 1 || limit > MAX_STATEMENT_PAGE) {
            throw new InvalidRequestException("limit must be between 1 and " + MAX_STATEMENT_PAGE);
        }
        Account account = visibleAccount(id, caller);
        // A striped parent's statement is its stripes' postings merged by seq (one global sequence), with the
        // summed balance; REPEATABLE READ keeps that sum and the postings in the same snapshot.
        List<UUID> postingAccounts = account.isStriped() ? stripeRepository.findStripeIds(id) : List.of(id);
        long balance = balanceOf(account);
        List<StatementLine> lines = statementRepository.page(
                postingAccounts, account.getType().getNormalBalance(), balance, before, limit);
        Long next = lines.size() == limit ? lines.get(lines.size() - 1).seq() : null;
        return new AccountStatementResponse(id, account.getCurrency(), balance, lines, next);
    }

    @Transactional(readOnly = true)
    public long calculateProjectedBalance(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return postingRepository.computeProjectedBalance(accountId, account.getType().getNormalBalance());
    }

    /** Someone else's account is reported as "not found" (404), so its existence is not revealed. */
    private Account visibleAccount(UUID id, ApiClient caller) {
        Account account = accountRepository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        if (!caller.isAdmin() && !account.isOwnedBy(caller.clientId())) {
            throw new AccountNotFoundException(id);
        }
        return account;
    }
}
