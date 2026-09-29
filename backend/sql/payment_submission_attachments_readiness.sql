-- Read-only preflight. A missing child table is a hard SQL failure (the caller
-- uses ON_ERROR_STOP); when the schema exists the result is exactly one row.
SELECT CASE WHEN
    to_regclass('payment_submission_attachments') IS NOT NULL
    AND (SELECT COUNT(*) = 4 FROM information_schema.columns
         WHERE table_schema = current_schema() AND table_name = 'payment_submission_attachments'
           AND ((column_name = 'id' AND data_type = 'bigint' AND is_nullable = 'NO')
             OR (column_name = 'submission_id' AND data_type = 'bigint' AND is_nullable = 'NO')
             OR (column_name = 'file_key' AND data_type = 'text' AND is_nullable = 'NO')
             OR (column_name = 'position' AND data_type = 'integer' AND is_nullable = 'NO')))
    AND EXISTS (SELECT 1 FROM pg_constraint c
        WHERE c.conrelid = 'payment_submission_attachments'::regclass AND c.contype = 'p'
          AND pg_get_constraintdef(c.oid) = 'PRIMARY KEY (id)')
    AND EXISTS (SELECT 1 FROM pg_constraint c
        WHERE c.conrelid = 'payment_submission_attachments'::regclass AND c.contype = 'f'
          AND c.confrelid = 'payment_submissions'::regclass
          AND c.convalidated
          AND c.confdeltype = 'c'
          AND pg_get_constraintdef(c.oid) LIKE 'FOREIGN KEY (submission_id) REFERENCES payment_submissions(id)%')
    AND EXISTS (SELECT 1 FROM pg_constraint c
        WHERE c.conrelid = 'payment_submission_attachments'::regclass AND c.contype = 'u'
          AND replace(pg_get_constraintdef(c.oid), '"', '') = 'UNIQUE (submission_id, position)')
    AND EXISTS (SELECT 1 FROM pg_constraint c
        WHERE c.conrelid = 'payment_submission_attachments'::regclass AND c.contype = 'c'
          AND c.convalidated AND replace(pg_get_constraintdef(c.oid), '"', '') LIKE '%position >= 0%'
          AND replace(pg_get_constraintdef(c.oid), '"', '') LIKE '%position < 5%')
    AND EXISTS (SELECT 1 FROM pg_indexes
        WHERE schemaname = current_schema() AND tablename = 'payment_submission_attachments'
          AND indexname = 'idx_payment_submission_attachments_submission'
          AND indexdef LIKE '%(submission_id)%')
    AND NOT EXISTS (SELECT 1 FROM payment_submission_attachments a
        WHERE a.position NOT BETWEEN 0 AND 4 OR a.file_key IS NULL OR btrim(a.file_key) = '')
    AND NOT EXISTS (SELECT 1 FROM payment_submission_attachments
        GROUP BY submission_id HAVING COUNT(*) > 5 OR MIN(position) < 0 OR MAX(position) >= 5)
    AND NOT EXISTS (SELECT 1 FROM payment_submissions p
        WHERE p.file_key IS NOT NULL AND btrim(p.file_key) <> '' AND NOT EXISTS (
            SELECT 1 FROM payment_submission_attachments a
            WHERE a.submission_id = p.id AND a.file_key = p.file_key))
    AND NOT EXISTS (SELECT 1 FROM payment_submissions p
        WHERE EXISTS (SELECT 1 FROM payment_submission_attachments a WHERE a.submission_id = p.id)
          AND p.file_key IS DISTINCT FROM (SELECT a.file_key FROM payment_submission_attachments a
              WHERE a.submission_id = p.id ORDER BY a.position LIMIT 1))
    THEN 'READY' ELSE 'NOT_READY' END AS readiness;
