package com.ledgerline.domain.repository;

import com.ledgerline.domain.model.IdempotencyRecord;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for idempotency records. All calls must run inside the transaction that
 * performs the guarded transfer.
 */
public interface IdempotencyRepository {

    /**
     * Tries to claim {@code (clientId, key)} for this transaction.
     * If another in-flight transaction holds the same claim, this blocks until it commits or rolls back.
     *
     * @return true if this transaction now owns the key; false if a committed record already exists
     */
    boolean claim(String clientId, String key, String requestHash);

    Optional<IdempotencyRecord> find(String clientId, String key);

    /** Links the claimed key to the journal entry it produced. */
    void complete(String clientId, String key, UUID journalEntryId);
}
