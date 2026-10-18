package com.ledgerline.infrastructure.persistence;

import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.repository.AccountRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JpaAccountRepository extends JpaRepository<Account, UUID>, AccountRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
    List<Account> findByIdInOrderByIdAscForUpdate(@Param("ids") List<UUID> ids);
}
