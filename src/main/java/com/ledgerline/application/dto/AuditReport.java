package com.ledgerline.application.dto;

import java.util.List;
import java.util.UUID;

/**
 * @param skipped accounts whose audit could not complete this round (e.g. serialization conflict
 *                with a concurrent transfer); they are simply audited again next round
 */
public record AuditReport(int audited, List<AuditResult> discrepancies, List<UUID> skipped) {
}
