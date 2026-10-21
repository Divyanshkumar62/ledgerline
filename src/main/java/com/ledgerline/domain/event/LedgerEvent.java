package com.ledgerline.domain.event;

import java.util.UUID;

/**
 * A fact the outside world may react to. Written to the outbox in the same transaction as the
 * ledger change that caused it, then published at least once.
 */
public sealed interface LedgerEvent permits TransferCompleted, LedgerDriftDetected, AccountAdminActionTaken {

    /** Stable event type name, part of the public contract (e.g. "TransferCompleted"). */
    String type();

    /** Partition key: all events of one aggregate are delivered in order relative to each other. */
    String aggregateKey();

    /** The journal entry this event describes, or null if it is not about one. */
    UUID journalEntryId();
}
