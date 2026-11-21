package com.ledgerline.application.service;

import com.ledgerline.application.dto.TransferResponse;

import java.time.Duration;
import java.util.Optional;

/**
 * Fast-path idempotency absorption port (ADR-015).
 * Allows replaying completed requests and absorbing concurrent duplicate stampedes
 * in a fast cache layer before hitting PostgreSQL.
 */
public interface IdempotencyFastPath {

    /**
     * Tries to find a completed cached response.
     * Throws IdempotencyKeyReusedException if a cached response exists with a different request hash.
     */
    Optional<TransferResponse> findCompleted(String clientId, String idempotencyKey, String requestHash);

    /**
     * Tries to acquire a short-lived in-flight lease for the given request.
     * Throws IdempotencyKeyReusedException if an in-flight lease exists with a different request hash.
     */
    Lease acquireLease(String clientId, String idempotencyKey, String requestHash);

    /**
     * Polls the fast-path cache for a completed response produced by a concurrent peer holding the in-flight lease.
     */
    Optional<TransferResponse> pollForCompleted(String clientId, String idempotencyKey, String requestHash, Duration timeout);

    /**
     * Caches the completed transfer response and releases any active in-flight lease.
     */
    void complete(String clientId, String idempotencyKey, String requestHash, TransferResponse response);

    /**
     * Releases an in-flight lease without caching a completed response (e.g. on rollback or error).
     */
    void releaseLease(String clientId, String idempotencyKey);

    interface Lease extends AutoCloseable {
        boolean acquired();

        @Override
        void close();
    }
}
