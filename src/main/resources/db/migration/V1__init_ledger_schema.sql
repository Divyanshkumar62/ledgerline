-- V1: Initial double-entry ledger schema
-- Core tables: accounts, journal_entries, postings
-- Invariants enforced: Immutability (append-only) and Zero-Sum (deferred constraint trigger)

-- 1. Accounts table
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    disallow_negative_balance BOOLEAN NOT NULL DEFAULT TRUE,
    balance BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_accounts_currency ON accounts(currency);

-- 2. Journal Entries table
CREATE TABLE journal_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_reference VARCHAR(255),
    description TEXT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_journal_entries_idempotency ON journal_entries(idempotency_reference);

-- 3. Postings table (legs of each journal entry)
CREATE TABLE postings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_entry_id UUID NOT NULL REFERENCES journal_entries(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    direction VARCHAR(10) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_postings_journal_entry ON postings(journal_entry_id);
CREATE INDEX idx_postings_account ON postings(account_id);

-- 4. Immutability Trigger Function
-- Postings and journal entries are strictly append-only. No UPDATE or DELETE permitted.
CREATE OR REPLACE FUNCTION prevent_financial_record_modifications()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Ledger records are immutable. UPDATE or DELETE operation is prohibited on table %', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_immutability_journal_entries
BEFORE UPDATE OR DELETE ON journal_entries
FOR EACH ROW
EXECUTE FUNCTION prevent_financial_record_modifications();

CREATE TRIGGER trg_immutability_postings
BEFORE UPDATE OR DELETE ON postings
FOR EACH ROW
EXECUTE FUNCTION prevent_financial_record_modifications();

-- 5. Zero-Sum Balance Invariant Constraint Trigger Function
-- For every committed journal entry, SUM(Debits) must strictly equal SUM(Credits).
-- Furthermore, an entry must contain at least two postings.
CREATE OR REPLACE FUNCTION check_journal_entry_zero_sum()
RETURNS TRIGGER AS $$
DECLARE
    v_net_balance BIGINT;
    v_leg_count INT;
BEGIN
    SELECT
        COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0),
        COUNT(*)
    INTO
        v_net_balance,
        v_leg_count
    FROM postings
    WHERE journal_entry_id = NEW.journal_entry_id;

    IF v_leg_count < 2 THEN
        RAISE EXCEPTION 'Journal entry % must have at least 2 legs, found %', NEW.journal_entry_id, v_leg_count;
    END IF;

    IF v_net_balance <> 0 THEN
        RAISE EXCEPTION 'Zero-sum balance invariant violated for journal entry %: net balance is % (must be 0)', NEW.journal_entry_id, v_net_balance;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Deferred constraint trigger: evaluated at COMMIT time so all multi-leg postings can be inserted
CREATE CONSTRAINT TRIGGER trg_postings_zero_sum
AFTER INSERT ON postings
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_journal_entry_zero_sum();
