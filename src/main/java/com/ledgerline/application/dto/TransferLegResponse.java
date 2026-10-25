package com.ledgerline.application.dto;

import com.ledgerline.domain.model.Posting;
import com.ledgerline.domain.model.PostingDirection;

import java.util.UUID;

public record TransferLegResponse(
        UUID postingId,
        UUID accountId,
        PostingDirection direction,
        long amountMinor,
        String currency
) {
    public static TransferLegResponse from(Posting posting) {
        return new TransferLegResponse(
                posting.getId(),
                posting.getAccount().getId(),
                posting.getDirection(),
                posting.getAmount(),
                posting.getCurrency()
        );
    }
}
