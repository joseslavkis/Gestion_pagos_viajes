-- Read-only, fail-closed preflight. Check actual canonical PostgreSQL rules,
-- not just constraint names. Legitimate missing legacy FX evidence is allowed.
WITH required(column_name, data_type, width, precision, scale, nullable) AS (
    VALUES
        ('currency', 'character varying', 3, NULL, NULL, 'NO'),
        ('exchange_rate', 'numeric', NULL, 18, 8, 'YES'),
        ('exchange_rate_scale', 'integer', NULL, NULL, NULL, 'YES'),
        ('exchange_rate_requested_date', 'date', NULL, NULL, NULL, 'YES'),
        ('exchange_rate_effective_date', 'date', NULL, NULL, NULL, 'YES'),
        ('exchange_rate_source', 'character varying', 64, NULL, NULL, 'YES'),
        ('exchange_rate_provider', 'character varying', 64, NULL, NULL, 'YES'),
        ('exchange_rate_provider_timestamp', 'character varying', 128, NULL, NULL, 'YES'),
        ('calculation_version', 'character varying', 16, NULL, NULL, 'YES'),
        ('reported_amount', 'numeric', NULL, 10, 2, 'NO'),
        ('amount_in_trip_currency', 'numeric', NULL, 10, 2, 'NO')
), required_constraints(name, definition) AS (
    VALUES
        ('ck_payment_outcomes_currency',
         'CHECKcurrency::text=ANYARRAY[''ARS''::charactervarying,''USD''::charactervarying]::text[]'),
        ('ck_payment_outcomes_exchange_rate_scale',
         'CHECKexchange_rate_scaleISNULLORexchange_rate_scale>=0ANDexchange_rate_scale<=8')
)
SELECT CASE WHEN
    NOT EXISTS (
        SELECT 1 FROM required r
        LEFT JOIN information_schema.columns c
          ON c.table_schema = current_schema() AND c.table_name = 'payment_outcomes'
         AND c.column_name = r.column_name
        WHERE c.column_name IS NULL OR c.data_type <> r.data_type
           OR c.is_nullable <> r.nullable
           OR (r.width IS NOT NULL AND c.character_maximum_length IS DISTINCT FROM r.width)
           OR (r.precision IS NOT NULL AND c.numeric_precision IS DISTINCT FROM r.precision)
           OR (r.scale IS NOT NULL AND c.numeric_scale IS DISTINCT FROM r.scale)
           OR c.column_default IS NOT NULL
    )
    AND NOT EXISTS (
        SELECT 1 FROM required_constraints r
        WHERE NOT EXISTS (
            SELECT 1 FROM pg_constraint c
            WHERE c.conrelid = to_regclass('payment_outcomes') AND c.conname = r.name
              AND c.contype = 'c' AND c.convalidated AND NOT c.connoinherit
              AND regexp_replace(pg_get_constraintdef(c.oid),
                  '[[:space:]()]', '', 'g') = r.definition
        )
    )
    -- JSON field access remains valid when a required column is absent, so the
    -- catalog checks above can return NOT_READY rather than a parse error.
    AND NOT EXISTS (
        SELECT 1 FROM payment_outcomes outcome
        WHERE (to_jsonb(outcome)->>'currency') IS NULL
           OR (to_jsonb(outcome)->>'currency') NOT IN ('ARS', 'USD')
    )
    THEN 'READY' ELSE 'NOT_READY' END AS readiness;
