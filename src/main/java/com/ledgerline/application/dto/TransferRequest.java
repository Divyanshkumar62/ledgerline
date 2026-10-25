package com.ledgerline.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Transfer payload. The idempotency key is NOT part of the body: it travels in the
 * {@code Idempotency-Key} header, so the body fingerprint describes only what is being moved.
 */
public record TransferRequest(
        @NotBlank(message = "Description is required")
        String description,

        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
        String currency,

        @NotEmpty(message = "Transfer must contain at least 2 legs")
        @Size(min = 2, message = "Transfer must contain at least 2 legs")
        List<@Valid TransferLegRequest> legs
) {}
