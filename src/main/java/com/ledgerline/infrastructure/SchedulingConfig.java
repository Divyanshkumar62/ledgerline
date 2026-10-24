package com.ledgerline.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background workers: the outbox relay (ADR-007) and the reconciliation audit (ADR-008). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
