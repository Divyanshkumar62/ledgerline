package com.ledgerline.application.dto;

import com.ledgerline.domain.model.PostingDirection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record TransferLegRequest(
        @NotNull(message = "Account ID is required")
        UUID accountId,

        @NotNull(message = "Posting direction is required")
        PostingDirection direction,

        @Positive(message = "Amount must be strictly greater than zero")
        long amountMinor
) {}
