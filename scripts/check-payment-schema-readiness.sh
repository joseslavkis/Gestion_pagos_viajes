#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
READINESS_QUERY="$ROOT_DIR/backend/sql/payment_money_schema_readiness.sql"

schema_state="$(docker compose --project-directory "$ROOT_DIR" \
  --file "$ROOT_DIR/docker-compose.yml" \
  exec -T db sh -c \
  'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atq' \
  < "$READINESS_QUERY")"

if [[ "$schema_state" != "READY" ]]; then
  printf 'Payment schema is incompatible; backend deployment was not started. Apply and verify the approved migration first.\n' >&2
  exit 1
fi

printf 'Payment schema preflight passed.\n'
