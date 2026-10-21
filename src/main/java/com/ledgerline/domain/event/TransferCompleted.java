package com.ledgerline.domain.event;

import com.ledgerline.domain.model.PostingDirection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A journal entry committed. Published to {@code ledger.transfers.v1}, keyed by journal entry.
 * {@code currency} is null for a multi-currency (FX) entry; every leg carries its own currency.
 */
public record TransferCompleted(UUID journalEntryId, String description, String currency,
                                List<Leg> legs, Instant committedAt) implements LedgerEvent {

    public record Leg(UUID accountId, PostingDirection direction, long amountMinor, String currency) {
    }

    @Override
    public String type() {
        return "TransferCompleted";
    }

    @Override
    public String aggregateKey() {
        return journalEntryId.toString();
    }
}
