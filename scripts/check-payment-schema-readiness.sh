#!/usr/bin/env bash

set -Eeuo pipefail

schema_state="$(docker compose exec -T db sh -c \
  'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atq' <<'SQL'
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
           AND column_default LIKE '%v1%'
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
    )
    THEN 'READY'
    ELSE 'NOT_READY'
END;
SQL
)"

if [[ "$schema_state" != "READY" ]]; then
  printf 'Payment schema is incompatible; backend deployment was not started. Apply and verify the approved migration first.\n' >&2
  exit 1
fi

printf 'Payment schema preflight passed.\n'
