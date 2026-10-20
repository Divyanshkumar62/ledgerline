package com.ledgerline.domain.exception;

import java.util.UUID;

/** The audit froze this account after detecting balance drift; it accepts no transfers until investigated. */
public class AccountFrozenException extends RuntimeException {
    private final UUID accountId;

    public AccountFrozenException(UUID accountId) {
        super("Account " + accountId + " is frozen pending reconciliation and cannot take part in transfers");
        this.accountId = accountId;
    }

    public UUID getAccountId() {
        return accountId;
    }
}
