package com.ledgerline.domain.repository;

import com.ledgerline.domain.model.PostingDirection;
import com.ledgerline.domain.model.PostingWindow;
import com.ledgerline.domain.model.ReconciliationCheckpoint;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain port for the audit job (ADR-008). */
public interface ReconciliationRepository {

    List<UUID> allAccountIds();

    Optional<ReconciliationCheckpoint> findCheckpoint(UUID accountId);

    void saveCheckpoint(ReconciliationCheckpoint checkpoint);

    /**
     * @param normalBalance the account type's normal side, used to sign each posting
     * @param horizon       only postings created before this instant may be folded into a new checkpoint
     */
    PostingWindow postingsAfter(UUID accountId, long afterSeq, PostingDirection normalBalance, Instant horizon);

    /** Freezes the account and bumps its version so in-flight optimistic writers fail and re-read. */
    void freeze(UUID accountId);
}
