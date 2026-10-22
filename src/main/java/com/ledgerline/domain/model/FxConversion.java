package com.ledgerline.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * The conversion behind one FX journal entry (ADR-012).
 *
 * @param rate target currency units per ONE source currency unit, in major units (e.g. 0.92 EUR per USD)
 */
public record FxConversion(UUID journalEntryId, String sourceCurrency, String targetCurrency,
                           long sourceAmountMinor, long targetAmountMinor, BigDecimal rate, RoundingMode roundingMode) {
}
