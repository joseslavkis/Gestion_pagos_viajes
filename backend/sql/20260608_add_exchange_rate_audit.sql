-- Add exchange-rate audit fields to payment_submissions.
-- Existing rows remain NULL. Safe to run on populated tables.

ALTER TABLE payment_submissions
    ADD COLUMN IF NOT EXISTS exchange_rate_requested_date DATE;

ALTER TABLE payment_submissions
    ADD COLUMN IF NOT EXISTS exchange_rate_effective_date DATE;

ALTER TABLE payment_submissions
    ADD COLUMN IF NOT EXISTS exchange_rate_source VARCHAR(64);

ALTER TABLE payment_submissions
    ADD COLUMN IF NOT EXISTS exchange_rate_provider_timestamp VARCHAR(128);
