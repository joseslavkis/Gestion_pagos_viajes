-- Widen and complete the immutable exchange-rate snapshot on payment_submissions.
-- This migration is additive, preserves existing financial history, and is safe to rerun.

BEGIN;

DO $$
DECLARE
    current_precision INTEGER;
    current_scale INTEGER;
BEGIN
    SELECT numeric_precision, numeric_scale
    INTO current_precision, current_scale
    FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name = 'payment_submissions'
      AND column_name = 'exchange_rate';

    IF current_precision IS DISTINCT FROM 18 OR current_scale IS DISTINCT FROM 8 THEN
        ALTER TABLE payment_submissions
            ALTER COLUMN exchange_rate TYPE NUMERIC(18,8)
            USING exchange_rate::NUMERIC(18,8);
    END IF;
END
$$;

ALTER TABLE payment_submissions
    ADD COLUMN IF NOT EXISTS exchange_rate_scale INTEGER;

ALTER TABLE payment_submissions
    ADD COLUMN IF NOT EXISTS exchange_rate_provider VARCHAR(64);

ALTER TABLE payment_submissions
    ADD COLUMN IF NOT EXISTS calculation_version VARCHAR(16);

-- Rows created before this migration were persisted through NUMERIC(10,2).
-- Record that known storage scale without reconstructing any lost precision.
UPDATE payment_submissions
SET exchange_rate_scale = 2
WHERE exchange_rate IS NOT NULL
  AND exchange_rate_scale IS NULL;

-- Legacy provider identity follows the documented source-as-provider convention.
-- A missing source remains missing rather than receiving a fabricated provider.
UPDATE payment_submissions
SET exchange_rate_provider = exchange_rate_source
WHERE exchange_rate_provider IS NULL
  AND exchange_rate_source IS NOT NULL;

-- Legacy rows are handled explicitly by the application as calculation version v1.
UPDATE payment_submissions
SET calculation_version = 'v1'
WHERE calculation_version IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'payment_submissions'::regclass
          AND conname = 'ck_payment_submissions_exchange_rate_scale'
    ) THEN
        ALTER TABLE payment_submissions
            ADD CONSTRAINT ck_payment_submissions_exchange_rate_scale
            CHECK (exchange_rate_scale IS NULL OR exchange_rate_scale BETWEEN 0 AND 8);
    END IF;
END
$$;

COMMIT;
