-- V4: Self-audit / reconciliation (ADR-008)

-- A frozen account rejects every new transfer. Set by the audit job when drift is detected.
ALTER TABLE accounts ADD COLUMN frozen BOOLEAN NOT NULL DEFAULT FALSE;

-- Ordered cursor over postings so the audit only reads what happened since the last checkpoint.
-- Adding an identity column rewrites the table; row-level triggers (immutability) do not fire on a rewrite.
ALTER TABLE postings ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX idx_postings_account_seq ON postings (account_id, seq);

-- Last verified state per account: the balance was exactly verified_balance after applying every
-- posting with seq <= last_posting_seq.
CREATE TABLE reconciliation_checkpoints (
    account_id       UUID PRIMARY KEY REFERENCES accounts(id),
    verified_balance BIGINT NOT NULL,
    last_posting_seq BIGINT NOT NULL,
    checked_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
