-- RUN ONLY AFTER:
-- - the version without fine logic is deployed in production;
-- - production smoke tests have passed;
-- - the rollback window is closed;
-- - a database backup is available and verified.
-- Do not execute this script as part of the application deploy.
--
-- This script intentionally aborts instead of repairing financial data.

BEGIN;

LOCK TABLE trips, installments IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    trips_with_fine BIGINT;
    installments_with_fine BIGINT;
    inconsistent_totals BIGINT;
BEGIN
    SELECT COUNT(*)
    INTO trips_with_fine
    FROM trips
    WHERE fixed_fine_amount <> 0;

    IF trips_with_fine <> 0 THEN
        RAISE EXCEPTION
            'Cannot drop fixed_fine_amount: % trip rows contain non-zero values',
            trips_with_fine;
    END IF;

    SELECT COUNT(*)
    INTO installments_with_fine
    FROM installments
    WHERE fine_amount <> 0;

    IF installments_with_fine <> 0 THEN
        RAISE EXCEPTION
            'Cannot drop fine_amount: % installment rows contain non-zero values',
            installments_with_fine;
    END IF;

    SELECT COUNT(*)
    INTO inconsistent_totals
    FROM installments
    WHERE total_due IS DISTINCT FROM capital_amount;

    IF inconsistent_totals <> 0 THEN
        RAISE EXCEPTION
            'Cannot drop fine columns: % installments have total_due different from capital_amount',
            inconsistent_totals;
    END IF;
END
$$;

ALTER TABLE installments
    DROP COLUMN fine_amount;

ALTER TABLE trips
    DROP COLUMN fixed_fine_amount;

COMMIT;
