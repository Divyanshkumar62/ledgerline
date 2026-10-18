package com.ledgerline.domain.repository;

import com.ledgerline.domain.model.Account;

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

    Optional<Account> findById(UUID id);

    /**
     * Finds and pessimistically locks accounts in ascending UUID order.
     */
    List<Account> findByIdInOrderByIdAscForUpdate(List<UUID> ids);
}
