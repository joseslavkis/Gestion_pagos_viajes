#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for query in payment_money_schema_readiness.sql payment_submission_attachments_readiness.sql manual_imputation_schema_readiness.sql admin_review_currency_schema_readiness.sql; do
  if ! schema_state="$(docker compose --project-directory "$ROOT_DIR" \
    --file "$ROOT_DIR/docker-compose.yml" \
    exec -T db sh -c \
    'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atq' \
    < "$ROOT_DIR/backend/sql/$query")"; then
    printf 'Payment schema is incompatible: %s query failed; backend deployment was not started.\n' "$query" >&2
    exit 1
  fi
  if [[ "$schema_state" != "READY" ]]; then
    printf 'Payment schema is incompatible: %s returned %q (expected READY); backend deployment was not started.\n' "$query" "$schema_state" >&2
    exit 1
  fi
done

printf 'Payment schema preflight passed.\n'
