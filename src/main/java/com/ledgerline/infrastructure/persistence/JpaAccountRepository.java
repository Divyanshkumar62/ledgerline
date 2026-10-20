package com.ledgerline.infrastructure.persistence;

import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.repository.AccountRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JpaAccountRepository extends JpaRepository<Account, UUID>, AccountRepository {

    @Override
    @Query("SELECT a.id FROM Account a WHERE a.id IN :ids AND a.ownerClientId = :owner")
    List<UUID> findIdsOwnedBy(@Param("ids") Collection<UUID> ids, @Param("owner") String ownerClientId);

    @Override
    @Query("SELECT a.currency FROM Account a WHERE a.id = :id")
    Optional<String> findCurrencyById(@Param("id") UUID id);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
    List<Account> findByIdInOrderByIdAscForUpdate(@Param("ids") List<UUID> ids);

    // Read-check-write in ONE statement. If another transaction holds the row, PostgreSQL waits,
    // then re-evaluates the WHERE clause against the newly committed balance, so the guard
    // can never act on a stale value.
    @Override
    @Modifying
    @Query(value = """
            UPDATE accounts
               SET balance = balance + :delta,
                   version = version + 1,
                   updated_at = clock_timestamp()
             WHERE id = :id
               AND NOT frozen
               AND (balance + :delta >= 0 OR NOT disallow_negative_balance)
            """, nativeQuery = true)
    int applyBalanceDelta(@Param("id") UUID id, @Param("delta") long delta);
}
