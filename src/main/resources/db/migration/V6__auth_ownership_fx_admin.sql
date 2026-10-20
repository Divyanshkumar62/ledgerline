-- V6: API-key authentication + account ownership (ADR-011), multi-currency FX (ADR-012),
--     admin repair / unfreeze audit trail (ADR-013).

-- 1. API clients. Only a SHA-256 of the key is stored; the key itself is shown once, at creation.
CREATE TABLE api_clients (
    client_id    VARCHAR(64)  PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    role         VARCHAR(16)  NOT NULL CHECK (role IN ('CLIENT', 'ADMIN', 'SYSTEM')),
    key_hash     CHAR(64)     UNIQUE,              -- NULL: cannot authenticate (the SYSTEM owner)
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    CHECK (role = 'SYSTEM' OR key_hash IS NOT NULL)
);

-- Owner of ledger-internal accounts (FX clearing, adjustments) and of accounts created before V6.
INSERT INTO api_clients (client_id, display_name, role) VALUES ('system', 'Ledger system accounts', 'SYSTEM');

-- 2. Every account has an owner; clients may only see and move their own.
ALTER TABLE accounts ADD COLUMN owner_client_id VARCHAR(64) REFERENCES api_clients (client_id);
UPDATE accounts SET owner_client_id = 'system' WHERE owner_client_id IS NULL;
ALTER TABLE accounts ALTER COLUMN owner_client_id SET NOT NULL;
CREATE INDEX idx_accounts_owner ON accounts (owner_client_id);

-- Ledger-internal accounts are found by purpose: one per (purpose, currency).
ALTER TABLE accounts ADD COLUMN system_purpose VARCHAR(32);
CREATE UNIQUE INDEX uq_accounts_system_purpose ON accounts (system_purpose, currency) WHERE system_purpose IS NOT NULL;

-- 3. Multi-currency journal entries: an FX entry has no single currency.
ALTER TABLE journal_entries ALTER COLUMN currency DROP NOT NULL;

-- The rate and amounts of every conversion, kept with the entry it produced.
CREATE TABLE fx_conversions (
    journal_entry_id   UUID PRIMARY KEY REFERENCES journal_entries (id) DEFERRABLE INITIALLY DEFERRED,
    source_currency    VARCHAR(3)     NOT NULL,
    target_currency    VARCHAR(3)     NOT NULL,
    source_amount      BIGINT         NOT NULL CHECK (source_amount > 0),
    target_amount      BIGINT         NOT NULL CHECK (target_amount > 0),
    rate               NUMERIC(24, 10) NOT NULL CHECK (rate > 0),  -- target units per source unit (major units)
    rounding_mode      VARCHAR(16)    NOT NULL,
    CHECK (source_currency <> target_currency)
);

-- Zero-sum is now enforced PER CURRENCY: summing all legs together would let USD +100 / EUR -100 "balance".
-- Also: a posting's currency must equal its account's currency.
CREATE OR REPLACE FUNCTION check_journal_entry_zero_sum()
RETURNS TRIGGER AS $$
DECLARE
    v_leg_count      INT;
    v_currency       VARCHAR(3);
    v_net_balance    BIGINT;
    v_bad_posting    UUID;
BEGIN
    SELECT COUNT(*) INTO v_leg_count FROM postings WHERE journal_entry_id = NEW.journal_entry_id;
    IF v_leg_count < 2 THEN
        RAISE EXCEPTION 'Journal entry % must have at least 2 legs, found %', NEW.journal_entry_id, v_leg_count;
    END IF;

    SELECT currency, SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END)
      INTO v_currency, v_net_balance
      FROM postings
     WHERE journal_entry_id = NEW.journal_entry_id
     GROUP BY currency
    HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Zero-sum balance invariant violated for journal entry % in %: net balance is % (must be 0)',
            NEW.journal_entry_id, v_currency, v_net_balance;
    END IF;

    SELECT p.id INTO v_bad_posting
      FROM postings p JOIN accounts a ON a.id = p.account_id
     WHERE p.journal_entry_id = NEW.journal_entry_id AND p.currency <> a.currency
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Posting % currency does not match its account currency', v_bad_posting;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 4. Who repaired, froze or unfroze which account, why, and what changed. Append-only like the ledger.
CREATE TABLE account_admin_actions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID         NOT NULL REFERENCES accounts (id),
    action           VARCHAR(32)  NOT NULL,
    reason           TEXT         NOT NULL CHECK (length(trim(reason)) > 0),
    actor_client_id  VARCHAR(64)  NOT NULL REFERENCES api_clients (client_id),
    balance_before   BIGINT,
    balance_after    BIGINT,
    journal_entry_id UUID REFERENCES journal_entries (id) DEFERRABLE INITIALLY DEFERRED,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_admin_actions_account ON account_admin_actions (account_id, created_at);

CREATE TRIGGER trg_immutability_admin_actions
BEFORE UPDATE OR DELETE ON account_admin_actions
FOR EACH ROW
EXECUTE FUNCTION prevent_financial_record_modifications();
