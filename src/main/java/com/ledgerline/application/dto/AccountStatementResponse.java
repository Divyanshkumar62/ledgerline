package com.ledgerline.application.dto;

import com.ledgerline.domain.model.StatementLine;

import java.util.List;
import java.util.UUID;

/**
 * Newest postings first. Pass {@code nextCursor} back as {@code before} for the next (older) page;
 * it is null on the last page.
 */
public record AccountStatementResponse(UUID accountId, String currency, long balance,
                                       List<StatementLine> postings, Long nextCursor) {
}
