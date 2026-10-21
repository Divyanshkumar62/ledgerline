package com.ledgerline.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * An admin froze, repaired or unfroze an account (ADR-013). Published to {@code ledger.accounts.v1}.
 *
 * @param action         FREEZE, REBUILD_BALANCE, BOOK_MISSING_POSTING or UNFREEZE
 * @param journalEntryId the correcting entry, for BOOK_MISSING_POSTING; otherwise null
 */
public record AccountAdminActionTaken(UUID accountId, String action, String reason, String actorClientId,
                                      Long balanceBefore, Long balanceAfter, UUID journalEntryId,
                                      Instant occurredAt) implements LedgerEvent {

    @Override
    public String type() {
        return "AccountAdminActionTaken";
    }

    @Override
    public String aggregateKey() {
        return accountId.toString();
    }
}
