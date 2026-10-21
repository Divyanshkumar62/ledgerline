package com.ledgerline.domain.model;

import java.util.UUID;

/**
 * A committed idempotent request: which client sent which key, a fingerprint of what they
 * asked for, and the journal entry it produced.
 */
public record IdempotencyRecord(String clientId, String idempotencyKey, String requestHash, UUID journalEntryId) {
}
