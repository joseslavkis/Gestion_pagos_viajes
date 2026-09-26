-- Read-only production schema preflight. Keep this query shared with Testcontainers tests.
SELECT CASE
    WHEN COALESCE((
        SELECT data_type = 'numeric'
           AND numeric_precision = 18
           AND numeric_scale = 8
           AND is_nullable = 'YES'
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'payment_submissions'
          AND column_name = 'exchange_rate'
    ), false)
    AND COALESCE((
        SELECT data_type = 'integer' AND is_nullable = 'YES'
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'payment_submissions'
          AND column_name = 'exchange_rate_scale'
    ), false)
    AND COALESCE((
        SELECT data_type = 'character varying'
           AND character_maximum_length = 64
           AND is_nullable = 'YES'
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'payment_submissions'
          AND column_name = 'exchange_rate_provider'
    ), false)
    AND COALESCE((
        SELECT data_type = 'character varying'
           AND character_maximum_length = 16
           AND is_nullable = 'NO'
           AND column_default = quote_literal('v1') || '::character varying'
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'payment_submissions'
          AND column_name = 'calculation_version'
    ), false)
    AND EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'payment_submissions'::regclass
          AND conname = 'ck_payment_submissions_exchange_rate_scale'
          AND contype = 'c'
          AND convalidated
          AND regexp_replace(
              lower(pg_get_constraintdef(oid)),
              '[[:space:]()]',
              '',
              'g'
          ) = 'checkexchange_rate_scaleisnullorexchange_rate_scale>=0andexchange_rate_scale<=8'
    )
    AND NOT EXISTS (
        SELECT 1
        FROM payment_submissions submission
        JOIN trips trip ON trip.id = submission.trip_id
        WHERE submission.calculation_version = '2'
          AND submission.payment_currency <> trip.currency
          AND (
              submission.exchange_rate IS NULL
              OR submission.exchange_rate <= 0
              OR submission.exchange_rate_scale IS NULL
              OR submission.exchange_rate_provider IS NULL
              OR submission.exchange_rate_source IS NULL
              OR submission.exchange_rate_requested_date IS NULL
              OR submission.exchange_rate_effective_date IS NULL
          )
    )
    THEN 'READY'
    ELSE 'NOT_READY'
END;
