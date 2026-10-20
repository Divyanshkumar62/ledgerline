-- V5: indexes for the retention job (ADR-009). Both purges select old rows by timestamp.
CREATE INDEX idx_outbox_published_at ON outbox_events (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX idx_idempotency_created_at ON idempotency_records (created_at);
