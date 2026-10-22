package com.ledgerline.application.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Cross-currency transfer (ADR-012). The client supplies the rate (the ledger records it, it does not
 * quote prices): {@code rate} = target-currency units per ONE source-currency unit, in major units.
 * Example: 100.00 USD at 0.9150 EUR/USD → {"sourceAmountMinor":10000,"rate":0.9150} → 91.50 EUR.
 */
public record FxTransferRequest(
        @NotBlank(message = "Description is required")
        String description,

        @NotNull(message = "Source account is required")
        UUID fromAccountId,

        @NotNull(message = "Target account is required")
        UUID toAccountId,

        @Positive(message = "Amount must be strictly greater than zero")
        long sourceAmountMinor,

        @NotNull(message = "Rate is required")
        @Positive(message = "Rate must be positive")
        @Digits(integer = 14, fraction = 10, message = "Rate allows at most 14 integer and 10 fraction digits")
        BigDecimal rate
) {}
