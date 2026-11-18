package com.ledgerline.application.service;

import com.ledgerline.application.strategy.LedgerCommand;
import com.ledgerline.domain.exception.AccountFrozenException;
import com.ledgerline.domain.exception.InvalidRequestException;
import com.ledgerline.domain.model.StripeLayout;
import com.ledgerline.domain.repository.StripeRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hot-account striping (ADR-014): rewrites every leg that names a striped parent onto ONE of its stripes, before
 * the strategy locks anything. The stripe is then just another account in the lock set, so canonical lock order,
 * zero-sum, audit and every strategy work unchanged.
 *
 * <p>Rules enforced here (the database enforces the structural ones, see V7):
 * <ul>
 *   <li>a striped parent is receive-only: a leg that would lower its balance is rejected (no balance check exists);</li>
 *   <li>a stripe cannot be named directly, so nothing can bypass the receive-only rule through it;</li>
 *   <li>a frozen parent takes part in no transfer, like any frozen account.</li>
 * </ul>
 */
@Component
public class StripeRouter {

    private final StripeRepository stripeRepository;

    public StripeRouter(StripeRepository stripeRepository) {
        this.stripeRepository = stripeRepository;
    }

    /**
     * @param reference the idempotency reference ({@code clientId:key}), or null on the internal path
     * @return the command with striped parents replaced by a stripe; the same instance if no account is striped
     */
    public LedgerCommand route(LedgerCommand command, String reference) {
        // ponytail: one indexed query per transfer even when nothing is striped; stripe layouts never change after
        // creation, so an in-memory cache keyed by account id is the upgrade if this query ever shows up in profiles.
        Map<UUID, StripeLayout> layouts = stripeRepository.findLayouts(command.accountIds());
        if (layouts.isEmpty()) {
            return command;
        }
        Map<UUID, UUID> chosen = new HashMap<>(); // parent → stripe: every leg on one parent uses the same stripe
        List<LedgerCommand.Leg> legs = command.legs().stream().map(leg -> {
            StripeLayout layout = layouts.get(leg.accountId());
            if (layout == null) {
                return leg;
            }
            if (layout.isStripe()) {
                throw new InvalidRequestException("Account " + leg.accountId() + " is a stripe of account "
                        + layout.parentAccountId() + "; send the transfer to the parent account");
            }
            if (layout.frozen()) {
                throw new AccountFrozenException(leg.accountId());
            }
            if (layout.type().calculateSignedDelta(leg.direction(), leg.amountMinor()) < 0) {
                throw new InvalidRequestException("Account " + leg.accountId()
                        + " is striped and receive-only: a transfer may only increase its balance");
            }
            UUID stripe = chosen.computeIfAbsent(leg.accountId(),
                    parent -> layout.stripeIds().get(stripeIndex(reference, layout.stripeIds().size())));
            return new LedgerCommand.Leg(stripe, leg.direction(), leg.amountMinor());
        }).toList();
        return new LedgerCommand(command.description(), command.currency(), legs);
    }

    /**
     * Same reference → same stripe, on every node and after every restart (a name-based UUID is an MD5 of the bytes,
     * unlike {@code Object.hashCode}, which is not guaranteed stable). Uniform enough to spread load evenly.
     * The internal path has no reference, so it picks at random.
     */
    static int stripeIndex(String reference, int stripeCount) {
        if (reference == null) {
            return ThreadLocalRandom.current().nextInt(stripeCount);
        }
        long bits = UUID.nameUUIDFromBytes(reference.getBytes(StandardCharsets.UTF_8)).getLeastSignificantBits();
        return (int) Math.floorMod(bits, (long) stripeCount);
    }
}
