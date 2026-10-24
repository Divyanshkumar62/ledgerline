package com.ledgerline.infrastructure.audit;

import com.ledgerline.application.dto.AuditReport;
import com.ledgerline.application.service.LedgerAuditService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the reconciliation audit over every account on a fixed delay. */
@Component
@ConditionalOnProperty(name = "ledgerline.audit.enabled", havingValue = "true", matchIfMissing = true)
public class AuditScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditScheduler.class);

    private final LedgerAuditService auditService;
    private final Duration checkpointLag;
    /** Discrepancies found by the last full audit. Alert if this is ever above 0. */
    private final AtomicInteger lastDiscrepancies = new AtomicInteger();

    public AuditScheduler(LedgerAuditService auditService,
                          @Value("${ledgerline.audit.checkpoint-lag:60s}") Duration checkpointLag,
                          MeterRegistry meterRegistry) {
        this.auditService = auditService;
        this.checkpointLag = checkpointLag;
        Gauge.builder("ledger.audit.discrepancies", lastDiscrepancies, AtomicInteger::get)
                .description("Accounts whose balance disagreed with their postings in the last full audit (should be 0)")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${ledgerline.audit.interval-ms:60000}", initialDelayString = "${ledgerline.audit.interval-ms:60000}")
    public void run() {
        AuditReport report = auditService.auditAll(Instant.now().minus(checkpointLag));
        lastDiscrepancies.set(report.discrepancies().size());
        log.info("Audit: {} accounts verified, {} discrepancies, {} skipped",
                report.audited(), report.discrepancies().size(), report.skipped().size());
    }
}
