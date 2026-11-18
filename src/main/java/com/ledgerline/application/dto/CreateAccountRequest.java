package com.ledgerline.application.dto;

import com.ledgerline.domain.model.AccountType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Accounts always open at a zero balance, owned by the calling client. Money only enters an account
 * through a journal entry, so the cached balance always equals the sum of its postings.
 *
 * <p>{@code stripeCount} (optional, ADR-014): split a hot, receive-only account into that many stripes. Fixed at
 * creation. A striped account cannot have {@code disallowNegativeBalance}: it only ever receives money.
 */
public record CreateAccountRequest(
        @NotNull(message = "Account type is required")
        AccountType type,

        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
        String currency,

        boolean disallowNegativeBalance,

        @Min(value = 0, message = "stripeCount must be between 0 and 64")
        @Max(value = 64, message = "stripeCount must be between 0 and 64")
        Integer stripeCount
) {
    public int stripes() {
        return stripeCount == null ? 0 : stripeCount;
    }
}
