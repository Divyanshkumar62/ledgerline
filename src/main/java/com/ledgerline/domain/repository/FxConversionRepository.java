package com.ledgerline.domain.repository;

import com.ledgerline.domain.model.FxConversion;

import java.util.Optional;
import java.util.UUID;

public interface FxConversionRepository {

    void record(FxConversion conversion);

    Optional<FxConversion> findByJournalEntryId(UUID journalEntryId);
}
