package com.ledgerline.application.dto;

import com.ledgerline.domain.model.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotNull(message = "Account type is required")
        AccountType type,

        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
        String currency,

        boolean disallowNegativeBalance,

        @PositiveOrZero(message = "Initial balance cannot be negative")
        long initialBalance
) {}
