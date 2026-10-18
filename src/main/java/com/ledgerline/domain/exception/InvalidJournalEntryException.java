package com.ledgerline.domain.exception;

public class InvalidJournalEntryException extends RuntimeException {
    public InvalidJournalEntryException(String message) {
        super(message);
    }
}
