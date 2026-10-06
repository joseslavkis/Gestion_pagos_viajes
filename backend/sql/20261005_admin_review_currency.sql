-- Independent outcome snapshots. Apply only with the coordinated outcome writers.
-- No defaults: old writers must not silently label a new administrative decision.
BEGIN;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM (VALUES
            ('payment_currency'), ('exchange_rate'), ('exchange_rate_scale'),
            ('exchange_rate_requested_date'), ('exchange_rate_effective_date'),
            ('exchange_rate_source'), ('exchange_rate_provider'),
            ('exchange_rate_provider_timestamp'), ('calculation_version')
        ) AS required(column_name)
        WHERE NOT EXISTS (
            SELECT 1 FROM information_schema.columns c
            WHERE c.table_schema = current_schema()
              AND c.table_name = 'payment_submissions'
              AND c.column_name = required.column_name
        )
    ) THEN
        RAISE EXCEPTION 'Missing submission snapshot prerequisites: apply 20260608_add_exchange_rate_audit.sql and 20260917_payment_money_invariants.sql first';
    END IF;
END
$$;

ALTER TABLE payment_outcomes
    ADD COLUMN IF NOT EXISTS currency VARCHAR(3),
    ADD COLUMN IF NOT EXISTS exchange_rate NUMERIC(18,8),
    ADD COLUMN IF NOT EXISTS exchange_rate_scale INTEGER,
    ADD COLUMN IF NOT EXISTS exchange_rate_requested_date DATE,
    ADD COLUMN IF NOT EXISTS exchange_rate_effective_date DATE,
    ADD COLUMN IF NOT EXISTS exchange_rate_source VARCHAR(64),
    ADD COLUMN IF NOT EXISTS exchange_rate_provider VARCHAR(64),
    ADD COLUMN IF NOT EXISTS exchange_rate_provider_timestamp VARCHAR(128),
    ADD COLUMN IF NOT EXISTS calculation_version VARCHAR(16);

-- Currency marks an unbackfilled historical row. Copy only persisted evidence,
-- including NULLs; never refill an independent new snapshot on a later rerun.
-- Outcome amounts, status, allocations, and submission history stay untouched.
UPDATE payment_outcomes outcome
SET currency = submission.payment_currency,
    exchange_rate = submission.exchange_rate,
    exchange_rate_scale = submission.exchange_rate_scale,
    exchange_rate_requested_date = submission.exchange_rate_requested_date,
    exchange_rate_effective_date = submission.exchange_rate_effective_date,
    exchange_rate_source = submission.exchange_rate_source,
    exchange_rate_provider = submission.exchange_rate_provider,
    exchange_rate_provider_timestamp = submission.exchange_rate_provider_timestamp,
    calculation_version = submission.calculation_version
FROM payment_submissions submission
WHERE submission.id = outcome.submission_id AND outcome.currency IS NULL;

ALTER TABLE payment_outcomes ALTER COLUMN currency SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
        WHERE conrelid = 'payment_outcomes'::regclass
          AND conname = 'ck_payment_outcomes_currency') THEN
        ALTER TABLE payment_outcomes ADD CONSTRAINT ck_payment_outcomes_currency
            CHECK (currency IN ('ARS', 'USD'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
        WHERE conrelid = 'payment_outcomes'::regclass
          AND conname = 'ck_payment_outcomes_exchange_rate_scale') THEN
        ALTER TABLE payment_outcomes ADD CONSTRAINT ck_payment_outcomes_exchange_rate_scale
            CHECK (exchange_rate_scale IS NULL OR exchange_rate_scale BETWEEN 0 AND 8);
    END IF;
END
$$;

ALTER TABLE payment_outcomes VALIDATE CONSTRAINT ck_payment_outcomes_currency;
ALTER TABLE payment_outcomes VALIDATE CONSTRAINT ck_payment_outcomes_exchange_rate_scale;

COMMIT;
