-- V3: Transactional outbox (ADR-007)
-- Domain events are inserted in the SAME transaction as the ledger change that caused them, so an
-- event exists if and only if its transfer committed. A relay publishes them to Kafka afterwards.

CREATE TABLE outbox_events (
    -- Allocated at INSERT. Monotonic per insert, NOT per commit: a lower number can commit after a
    -- higher one, and rolled-back inserts leave gaps. Consumers must not assume gap-free ordering.
    sequence_number  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id         UUID         NOT NULL UNIQUE,      -- consumers deduplicate on this
    event_type       VARCHAR(64)  NOT NULL,
    topic            VARCHAR(128) NOT NULL,
    event_key        VARCHAR(128) NOT NULL,             -- Kafka key: keeps one aggregate's events in one partition
    journal_entry_id UUID REFERENCES journal_entries(id) DEFERRABLE INITIALLY DEFERRED,
    payload          TEXT         NOT NULL,             -- JSON
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    published_at     TIMESTAMP WITH TIME ZONE
);

-- The relay only ever scans unpublished rows in sequence order.
CREATE INDEX idx_outbox_unpublished ON outbox_events (sequence_number) WHERE published_at IS NULL;
