package com.ledgerline.domain.repository;

import com.ledgerline.domain.model.Posting;
import com.ledgerline.domain.model.PostingDirection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for Posting persistence and audit queries.
 */
public interface PostingRepository {

    Posting save(Posting posting);

    <S extends Posting> List<S> saveAll(Iterable<S> entities);

    Optional<Posting> findById(UUID id);

    List<Posting> findByJournalEntryId(UUID journalEntryId);

    List<Posting> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

    long computeProjectedBalance(UUID accountId, PostingDirection normalDirection);
}
