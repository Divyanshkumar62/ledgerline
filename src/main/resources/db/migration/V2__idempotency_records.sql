-- V2: DB-first idempotency (ADR-006)
-- One row per (client, key). The row is inserted in the SAME transaction as the transfer it guards,
-- so it only becomes visible when the transfer commits. A concurrent duplicate's
-- INSERT ... ON CONFLICT DO NOTHING waits on the uncommitted row, then either finds the committed
-- record (replay) or, if the original rolled back, inserts its own claim and proceeds.

CREATE TABLE idempotency_records (
    client_id        VARCHAR(64)  NOT NULL,
    idempotency_key  VARCHAR(128) NOT NULL,
    -- SHA-256 (hex) of the canonical request; detects key reuse with a different payload (422)
    request_hash     CHAR(64)     NOT NULL,
    -- Set before COMMIT in the claiming transaction, so every committed row has it.
    -- The FK is deferred because the journal entry is inserted by Hibernate at flush,
    -- after this row is updated.
    journal_entry_id UUID REFERENCES journal_entries(id) DEFERRABLE INITIALLY DEFERRED,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (client_id, idempotency_key)
);
