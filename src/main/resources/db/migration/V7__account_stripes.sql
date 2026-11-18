-- V7: hot-account striping (ADR-014).
-- A striped account is split into N real child accounts ("stripes"). Transfers that credit the parent are
-- routed to one stripe, so concurrent writers lock different rows instead of queueing on one.
-- Only for receive-only accounts: every posting must INCREASE the balance, so no balance check is ever needed.

-- Parent: stripe_count > 0. Stripe: parent_account_id + stripe_index set. Plain account: neither.
ALTER TABLE accounts ADD COLUMN stripe_count INT NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD COLUMN parent_account_id UUID REFERENCES accounts (id);
ALTER TABLE accounts ADD COLUMN stripe_index INT;

ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_stripe_count CHECK (stripe_count BETWEEN 0 AND 64),
    ADD CONSTRAINT ck_accounts_stripe_link CHECK ((parent_account_id IS NULL) = (stripe_index IS NULL)),
    ADD CONSTRAINT ck_accounts_stripe_index CHECK (stripe_index IS NULL OR stripe_index >= 0),
    -- One level only: a stripe is never itself striped.
    ADD CONSTRAINT ck_accounts_stripe_not_nested CHECK (parent_account_id IS NULL OR stripe_count = 0),
    -- Striping removes the single row that a balance check would have to lock, so an account that must
    -- never go negative can never be striped (and neither can its stripes).
    ADD CONSTRAINT ck_accounts_striped_no_balance_check
        CHECK ((stripe_count = 0 AND parent_account_id IS NULL) OR NOT disallow_negative_balance);

-- Also the lookup index for "all stripes of this parent".
CREATE UNIQUE INDEX uq_accounts_parent_stripe ON accounts (parent_account_id, stripe_index)
    WHERE parent_account_id IS NOT NULL;

-- The parent holds no postings of its own: its balance is the sum of its stripes. The application never routes
-- a posting to a parent; this trigger makes that a database guarantee too.
CREATE OR REPLACE FUNCTION reject_posting_on_striped_parent()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM accounts WHERE id = NEW.account_id AND stripe_count > 0) THEN
        RAISE EXCEPTION 'Account % is striped: postings must go to one of its stripes', NEW.account_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_postings_not_on_striped_parent
BEFORE INSERT ON postings
FOR EACH ROW
EXECUTE FUNCTION reject_posting_on_striped_parent();
