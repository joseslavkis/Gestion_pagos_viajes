-- Read-only preflight for manual administrative imputation columns.
SELECT CASE
    WHEN COALESCE((
        SELECT data_type IN ('character varying', 'text')
           AND is_nullable = 'NO'
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'payment_submissions'
          AND column_name = 'source'
    ), false)
    AND COALESCE((
        SELECT character_maximum_length = 500 AND is_nullable = 'YES'
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'payment_submissions'
          AND column_name = 'manual_reason'
    ), false)
    AND COALESCE((
        SELECT is_nullable = 'YES'
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'payment_submissions'
          AND column_name = 'payment_method'
    ), false)
    AND EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'payment_submissions'::regclass
          AND conname = 'ck_payment_submissions_source'
    )
    AND EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'payment_submissions'::regclass
          AND conname = 'ck_payment_submissions_manual_not_pending'
    )
    AND NOT EXISTS (
        SELECT 1 FROM payment_submissions WHERE source IS NULL
    )
    AND NOT EXISTS (
        SELECT 1 FROM payment_submissions WHERE source = 'ADMIN_MANUAL' AND status = 'PENDING'
    )
    THEN 'READY'
    ELSE 'NOT_READY'
END;
