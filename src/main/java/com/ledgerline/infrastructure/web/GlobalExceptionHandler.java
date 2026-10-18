package com.ledgerline.infrastructure.web;

import com.ledgerline.domain.exception.AccountNotFoundException;
import com.ledgerline.domain.exception.CurrencyMismatchException;
import com.ledgerline.domain.exception.ImbalancedJournalEntryException;
import com.ledgerline.domain.exception.InsufficientFundsException;
import com.ledgerline.domain.exception.InvalidJournalEntryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
