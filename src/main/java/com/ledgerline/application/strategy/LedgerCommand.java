package com.ledgerline.application.strategy;

import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.domain.model.PostingDirection;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What a strategy executes: a set of legs, either all in one {@code currency} or, when {@code currency} is
 * null, spanning several (an FX entry that must balance per currency, ADR-012). Decoupled from the HTTP
 * request types so the same three strategies run both ordinary and FX transfers.
 */
public record LedgerCommand(String description, String currency, List<Leg> legs) {

    public record Leg(UUID accountId, PostingDirection direction, long amountMinor) {
    }

    public static LedgerCommand of(TransferRequest request) {
        return new LedgerCommand(request.description(), request.currency().trim().toUpperCase(Locale.ROOT),
                request.legs().stream().map(l -> new Leg(l.accountId(), l.direction(), l.amountMinor())).toList());
    }

    public List<UUID> accountIds() {
        return legs.stream().map(Leg::accountId).distinct().toList();
    }
}
