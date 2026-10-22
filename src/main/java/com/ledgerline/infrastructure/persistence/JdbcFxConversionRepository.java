package com.ledgerline.infrastructure.persistence;

import com.ledgerline.domain.model.FxConversion;
import com.ledgerline.domain.repository.FxConversionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcFxConversionRepository implements FxConversionRepository {

    private final JdbcTemplate jdbc;

    public JdbcFxConversionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(FxConversion c) {
        jdbc.update("""
                INSERT INTO fx_conversions (journal_entry_id, source_currency, target_currency, source_amount, target_amount,
                                            rate, rounding_mode)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, c.journalEntryId(), c.sourceCurrency(), c.targetCurrency(), c.sourceAmountMinor(),
                c.targetAmountMinor(), c.rate(), c.roundingMode().name());
    }

    @Override
    public Optional<FxConversion> findByJournalEntryId(UUID journalEntryId) {
        return jdbc.query("SELECT * FROM fx_conversions WHERE journal_entry_id = ?", (rs, i) -> new FxConversion(
                rs.getObject("journal_entry_id", UUID.class), rs.getString("source_currency"), rs.getString("target_currency"),
                rs.getLong("source_amount"), rs.getLong("target_amount"), rs.getBigDecimal("rate"),
                RoundingMode.valueOf(rs.getString("rounding_mode"))), journalEntryId).stream().findFirst();
    }
}
