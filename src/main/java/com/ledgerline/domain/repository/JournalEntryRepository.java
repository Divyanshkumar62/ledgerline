package com.ledgerline.domain.repository;

import com.ledgerline.domain.model.JournalEntry;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for JournalEntry persistence.
 */
public interface JournalEntryRepository {

    JournalEntry save(JournalEntry journalEntry);

    Optional<JournalEntry> findById(UUID id);

    Optional<JournalEntry> findByIdempotencyReference(String idempotencyReference);
}
