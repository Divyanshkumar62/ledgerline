package com.ledgerline.domain.model;

/**
 * An authenticated caller of the API (ADR-011). Owns the accounts it creates.
 * SYSTEM is the owner of ledger-internal accounts and can never authenticate.
 */
public record ApiClient(String clientId, String displayName, Role role, boolean active) {

    public enum Role { CLIENT, ADMIN, SYSTEM }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
