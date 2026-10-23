package com.ledgerline.domain.repository;

import java.util.UUID;

/** Append-only audit trail of admin interventions on accounts (ADR-013). */
public interface AdminActionRepository {

    void record(UUID accountId, String action, String reason, String actorClientId,
                Long balanceBefore, Long balanceAfter, UUID journalEntryId);
}
