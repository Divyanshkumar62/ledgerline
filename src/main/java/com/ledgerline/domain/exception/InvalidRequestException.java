package com.ledgerline.domain.exception;

/** A request that is well-formed JSON but asks for something the ledger does not allow (400). */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
