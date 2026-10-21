package com.ledgerline.application.dto;

/**
 * Result of an idempotent transfer. {@code replayed} is true when the response was rebuilt
 * from an earlier request with the same key, and no new ledger effect happened.
 */
public record TransferOutcome(TransferResponse response, boolean replayed) {
}
