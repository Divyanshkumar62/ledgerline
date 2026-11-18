package com.ledgerline.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * How one account takes part in striping (ADR-014): either a striped parent (with its stripe ids in index order)
 * or a stripe (with its parent). Plain accounts have no layout.
 */
public record StripeLayout(UUID accountId, AccountType type, boolean frozen, UUID parentAccountId, List<UUID> stripeIds) {

    public boolean isStripe() {
        return parentAccountId != null;
    }
}
