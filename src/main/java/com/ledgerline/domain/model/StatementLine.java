package com.ledgerline.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One posting on an account statement.
 *
 * @param signedAmountMinor the effect on THIS account's balance (+ increases it, per its normal side)
 * @param balanceAfterMinor the account balance immediately after this posting
 */
public record StatementLine(long seq, UUID postingId, UUID journalEntryId, String description,
                            PostingDirection direction, long amountMinor, String currency,
                            long signedAmountMinor, long balanceAfterMinor, Instant createdAt) {
}
