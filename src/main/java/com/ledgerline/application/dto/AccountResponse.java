package com.ledgerline.application.dto;

import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.AccountType;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        AccountType type,
        String currency,
        boolean disallowNegativeBalance,
        long balance,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getType(),
                account.getCurrency(),
                account.isDisallowNegativeBalance(),
                account.getBalance(),
                account.getVersion(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
