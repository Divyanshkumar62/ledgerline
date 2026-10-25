package com.ledgerline.infrastructure.web;

import com.ledgerline.domain.exception.AccountFrozenException;
import com.ledgerline.domain.exception.AccountNotFoundException;
import com.ledgerline.domain.exception.AccountStillDriftedException;
import com.ledgerline.domain.exception.InvalidRequestException;
import com.ledgerline.domain.exception.CurrencyMismatchException;
import com.ledgerline.domain.exception.IdempotencyKeyReusedException;
import com.ledgerline.domain.exception.ImbalancedJournalEntryException;
import com.ledgerline.domain.exception.InsufficientFundsException;
import com.ledgerline.domain.exception.InvalidJournalEntryException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ImbalancedJournalEntryException.class)
    public ProblemDetail handleImbalancedJournalEntry(ImbalancedJournalEntryException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Imbalanced Journal Entry");
        problem.setType(URI.create("https://ledgerline.com/errors/imbalanced-entry"));
        problem.setProperty("totalDebits", ex.getTotalDebits());
        problem.setProperty("totalCredits", ex.getTotalCredits());
        problem.setProperty("discrepancy", ex.getDiscrepancy());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Insufficient Funds");
        problem.setType(URI.create("https://ledgerline.com/errors/insufficient-funds"));
        problem.setProperty("accountId", ex.getAccountId());
        problem.setProperty("currentBalance", ex.getCurrentBalance());
        problem.setProperty("requestedDelta", ex.getRequestedDelta());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Currency Mismatch");
        problem.setType(URI.create("https://ledgerline.com/errors/currency-mismatch"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(AccountNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Account Not Found");
        problem.setType(URI.create("https://ledgerline.com/errors/account-not-found"));
        problem.setProperty("accountId", ex.getAccountId());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(InvalidJournalEntryException.class)
    public ProblemDetail handleInvalidJournalEntry(InvalidJournalEntryException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Journal Entry");
        problem.setType(URI.create("https://ledgerline.com/errors/invalid-entry"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Request");
        problem.setType(URI.create("https://ledgerline.com/errors/invalid-request"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(AccountStillDriftedException.class)
    public ProblemDetail handleStillDrifted(AccountStillDriftedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Account Still Drifted");
        problem.setType(URI.create("https://ledgerline.com/errors/account-still-drifted"));
        problem.setProperty("accountId", ex.getAccountId());
        problem.setProperty("discrepancy", ex.getDiscrepancy());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(AccountFrozenException.class)
    public ProblemDetail handleAccountFrozen(AccountFrozenException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.LOCKED, ex.getMessage());
        problem.setTitle("Account Frozen");
        problem.setType(URI.create("https://ledgerline.com/errors/account-frozen"));
        problem.setProperty("accountId", ex.getAccountId());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Idempotency Key Reused");
        problem.setType(URI.create("https://ledgerline.com/errors/idempotency-key-reused"));
        problem.setProperty("idempotencyKey", ex.getIdempotencyKey());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Lock contention (optimistic retries exhausted, lock timeout, deadlock victim). Nothing was
     * committed, so the client can safely retry. Never a 500: this is load, not a bug.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ProblemDetail> handleConcurrencyFailure(ConcurrencyFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The accounts involved are under heavy contention; the transfer was not applied. Retry later.");
        problem.setTitle("Concurrent Modification");
        problem.setType(URI.create("https://ledgerline.com/errors/concurrent-modification"));
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.CONFLICT).header(HttpHeaders.RETRY_AFTER, "1").body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationErrors(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, errors);
        problem.setTitle("Validation Error");
        problem.setType(URI.create("https://ledgerline.com/errors/validation-error"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
