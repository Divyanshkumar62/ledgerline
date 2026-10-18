package com.ledgerline.infrastructure.persistence;

import com.ledgerline.domain.model.JournalEntry;
import com.ledgerline.domain.repository.JournalEntryRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface JpaJournalEntryRepository extends JpaRepository<JournalEntry, UUID>, JournalEntryRepository {
}
