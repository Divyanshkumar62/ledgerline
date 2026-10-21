package com.ledgerline.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The audit proved that, after applying every posting with {@code seq <= lastPostingSeq}, the account's
 * balance was exactly {@code verifiedBalance}. The next audit only needs the postings after that.
 */
public record ReconciliationCheckpoint(UUID accountId, long verifiedBalance, long lastPostingSeq, Instant checkedAt) {

    public static ReconciliationCheckpoint origin(UUID accountId) {
        return new ReconciliationCheckpoint(accountId, 0L, 0L, Instant.EPOCH);
    }
}
