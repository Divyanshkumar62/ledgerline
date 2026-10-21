package com.ledgerline.domain.repository;

import com.ledgerline.domain.event.LedgerEvent;

/** Domain port for the transactional outbox. Must be called inside the transaction that caused the event. */
public interface OutboxRepository {

    void append(LedgerEvent event);
}
