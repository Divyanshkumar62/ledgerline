package com.ledgerline.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * PostgreSQL stores timestamps with microsecond precision and ROUNDS anything finer. The JVM clock
 * can tick in 100 ns steps (Windows), so an untruncated Instant would be reported in the first API
 * response but stored differently, and a later read or idempotent replay would disagree with it.
 */
final class LedgerTime {

    private LedgerTime() {
    }

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
