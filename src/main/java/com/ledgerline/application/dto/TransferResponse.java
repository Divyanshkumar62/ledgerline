package com.ledgerline.application.dto;

import com.ledgerline.domain.model.JournalEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferResponse(
        UUID journalEntryId,
        String description,
        String currency,
        String idempotencyReference,
        List<TransferLegResponse> legs,
        Instant createdAt
) {
    public static TransferResponse from(JournalEntry entry) {
        List<TransferLegResponse> legResponses = entry.getPostings().stream()
                .map(TransferLegResponse::from)
                .toList();

        return new TransferResponse(
                entry.getId(),
                entry.getDescription(),
                entry.getCurrency(),
                entry.getIdempotencyReference(),
                legResponses,
                entry.getCreatedAt()
        );
    }
}
