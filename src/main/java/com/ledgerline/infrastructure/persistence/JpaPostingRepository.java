package com.ledgerline.infrastructure.persistence;

import com.ledgerline.domain.model.Posting;
import com.ledgerline.domain.model.PostingDirection;
import com.ledgerline.domain.repository.PostingRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface JpaPostingRepository extends JpaRepository<Posting, UUID>, PostingRepository {

    @Override
    @Query("SELECT COALESCE(SUM(CASE WHEN p.direction = :normalDirection THEN p.amount ELSE -p.amount END), 0) " +
           "FROM Posting p WHERE p.account.id = :accountId")
    long computeProjectedBalance(@Param("accountId") UUID accountId,
                                 @Param("normalDirection") PostingDirection normalDirection);
}
