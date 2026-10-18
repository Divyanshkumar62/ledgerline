package com.ledgerline.application.service;

import com.ledgerline.application.dto.AccountResponse;
import com.ledgerline.application.dto.CreateAccountRequest;
import com.ledgerline.domain.exception.AccountNotFoundException;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.repository.AccountRepository;
import com.ledgerline.domain.repository.PostingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final PostingRepository postingRepository;

    public AccountService(AccountRepository accountRepository, PostingRepository postingRepository) {
        this.accountRepository = accountRepository;
        this.postingRepository = postingRepository;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        Account account = new Account(
                null,
                request.type(),
                request.currency(),
                request.disallowNegativeBalance(),
                request.initialBalance()
        );
        Account saved = accountRepository.save(account);
        return AccountResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public long calculateProjectedBalance(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return postingRepository.computeProjectedBalance(accountId, account.getType().getNormalBalance());
    }
}
