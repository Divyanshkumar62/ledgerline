package com.ledgerline.application.dto;

import com.ledgerline.domain.model.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Accounts always open at a zero balance, owned by the calling client. Money only enters an account
 * through a journal entry, so the cached balance always equals the sum of its postings.
 */
public record CreateAccountRequest(
        @NotNull(message = "Account type is required")
        AccountType type,

        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
        String currency,

        boolean disallowNegativeBalance
) {}
