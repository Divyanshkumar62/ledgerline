package com.ledgerline.domain.exception;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    private final UUID accountId;
    private final long currentBalance;
    private final long requestedDelta;

    public InsufficientFundsException(UUID accountId, long currentBalance, long requestedDelta) {
        super(String.format("Account %s has insufficient funds: current balance is %d, requested deduction is %d",
                accountId, currentBalance, Math.abs(requestedDelta)));
        this.accountId = accountId;
        this.currentBalance = currentBalance;
        this.requestedDelta = requestedDelta;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getCurrentBalance() {
        return currentBalance;
    }

    public long getRequestedDelta() {
        return requestedDelta;
    }
}
