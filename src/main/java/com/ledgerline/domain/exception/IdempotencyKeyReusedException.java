package com.ledgerline.domain.exception;

/**
 * The client reused an idempotency key with a different request payload. Replaying the old
 * result would silently ignore what they asked for; executing it would break idempotency.
 */
public class IdempotencyKeyReusedException extends RuntimeException {
    private final String idempotencyKey;

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("Idempotency key '" + idempotencyKey + "' was already used with a different request payload");
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
