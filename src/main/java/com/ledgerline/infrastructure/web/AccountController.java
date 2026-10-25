package com.ledgerline.infrastructure.web;

import com.ledgerline.application.dto.AccountResponse;
import com.ledgerline.application.dto.AccountStatementResponse;
import com.ledgerline.application.dto.CreateAccountRequest;
import com.ledgerline.application.service.AccountService;
import com.ledgerline.domain.model.ApiClient;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Accounts belong to the client that creates them; other clients get 404 (ADR-011). */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@AuthenticationPrincipal ApiClient client,
                                                         @Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request, client));
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@AuthenticationPrincipal ApiClient client, @PathVariable UUID id) {
        return accountService.getAccount(id, client);
    }

    /**
     * Statement, newest first. {@code before} = the {@code nextCursor} of the previous page.
     * Example: GET /api/v1/accounts/{id}/postings?limit=50  →  ...?limit=50&before=1234
     */
    @GetMapping("/{id}/postings")
    public AccountStatementResponse statement(@AuthenticationPrincipal ApiClient client, @PathVariable UUID id,
                                              @RequestParam(required = false) Long before,
                                              @RequestParam(defaultValue = "50") int limit) {
        return accountService.statement(id, client, before, limit);
    }
}
