package com.ledgerline.application.dto;

import java.util.UUID;

public record AdminActionResult(UUID accountId, String action, Long balanceBefore, Long balanceAfter,
                                boolean frozen, UUID journalEntryId) {
}
