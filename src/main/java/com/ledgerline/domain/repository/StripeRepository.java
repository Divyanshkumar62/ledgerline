package com.ledgerline.domain.repository;

import com.ledgerline.domain.model.StripeLayout;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read side of hot-account striping (ADR-014). Scalar reads only: no Account entity is loaded or locked. */
public interface StripeRepository {

    /** Layouts of those {@code accountIds} that are striped parents or stripes, in one query; plain accounts are absent. */
    Map<UUID, StripeLayout> findLayouts(Collection<UUID> accountIds);

    /** Stripe ids of a parent, in stripe-index order (empty for a plain account). */
    List<UUID> findStripeIds(UUID parentAccountId);

    /** Σ of the stripes' cached balances, in ONE statement so every stripe is read from the same snapshot. */
    long sumStripeBalances(UUID parentAccountId);
}
