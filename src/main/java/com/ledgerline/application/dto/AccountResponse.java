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
        boolean frozen,
        String ownerClientId,
        int stripeCount,
        UUID parentAccountId,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static AccountResponse from(Account account) {
        return from(account, account.getBalance());
    }

    /** {@code balance} is passed in for a striped parent, whose balance is the sum of its stripes (ADR-014). */
    public static AccountResponse from(Account account, long balance) {
        return new AccountResponse(
                account.getId(),
                account.getType(),
                account.getCurrency(),
                account.isDisallowNegativeBalance(),
                balance,
                account.isFrozen(),
                account.getOwnerClientId(),
                account.getStripeCount(),
                account.getParentAccountId(),
                account.getVersion(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
