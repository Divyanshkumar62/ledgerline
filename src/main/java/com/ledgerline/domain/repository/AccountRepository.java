package com.ledgerline.domain.repository;

import com.ledgerline.domain.model.Account;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for Account persistence.
 * Free of framework and infrastructure dependencies.
 */
public interface AccountRepository {

    Account save(Account account);

    <S extends Account> List<S> saveAll(Iterable<S> entities);

    /** Saves and immediately flushes the UPDATEs to the database (strategy B, see there). */
    <S extends Account> List<S> saveAllAndFlush(Iterable<S> entities);

    Optional<Account> findById(UUID id);

    /** The subset of {@code ids} owned by {@code ownerClientId} (ADR-011). Scalar: loads no entities. */
    List<UUID> findIdsOwnedBy(Collection<UUID> ids, String ownerClientId);

    /** Scalar read of an account's (immutable) currency. Loads no entity. */
    Optional<String> findCurrencyById(UUID id);

    /** Plain read with no row lock (strategies B and C). */
    List<Account> findAllById(Iterable<UUID> ids);

    /**
     * Finds and pessimistically locks accounts in ascending UUID order.
     */
    List<Account> findByIdInOrderByIdAscForUpdate(List<UUID> ids);

    /**
     * Atomically adds {@code delta} to the balance unless the account is frozen or the change would take a
     * negative-balance-disallowed account below zero. Bumps {@code version} so optimistic
     * readers notice the change.
     *
     * @return rows updated: 1 on success, 0 if the frozen or negative-balance guard rejected it
     */
    int applyBalanceDelta(UUID id, long delta);
}
