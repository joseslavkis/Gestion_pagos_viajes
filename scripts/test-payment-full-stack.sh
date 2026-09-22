#!/usr/bin/env bash

# Runs the payment browser contract against a real Spring backend and a
# disposable PostgreSQL container. The deterministic FX provider lives only on
# the Maven test classpath and is activated by the payment-fx-test profile.

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/payment-full-stack.XXXXXX")"
POSTGRES_CONTAINER="payment-full-stack-$PPID-$$"
BACKEND_PID=""
FRONTEND_PID=""
FX_CALL_LOG="$TMP_DIR/fx-calls.log"
BACKEND_LOG="$TMP_DIR/backend.log"
FRONTEND_LOG="$TMP_DIR/frontend.log"
ADMIN_EMAIL="payment-e2e-admin@example.com"
ADMIN_PASSWORD="Payment-E2e-Admin-2026!"

free_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

cleanup() {
  [[ -z "$FRONTEND_PID" ]] || kill "$FRONTEND_PID" 2>/dev/null || true
  [[ -z "$BACKEND_PID" ]] || kill "$BACKEND_PID" 2>/dev/null || true
  [[ -z "$FRONTEND_PID" ]] || wait "$FRONTEND_PID" 2>/dev/null || true
  [[ -z "$BACKEND_PID" ]] || wait "$BACKEND_PID" 2>/dev/null || true
  docker rm -f "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

command -v docker >/dev/null
command -v curl >/dev/null
command -v python3 >/dev/null
node -e "require.resolve('@playwright/test/package.json')" >/dev/null 2>&1 || {
  printf 'Playwright test dependency is unavailable. Install the pinned @playwright/test version before running this local harness.\n' >&2
  exit 2
}

BACKEND_PORT="$(free_port)"
FRONTEND_PORT="$(free_port)"
FRONTEND_URL="http://127.0.0.1:$FRONTEND_PORT"
BACKEND_URL="http://127.0.0.1:$BACKEND_PORT"

touch "$FX_CALL_LOG"
docker run --detach --rm \
  --name "$POSTGRES_CONTAINER" \
  --publish 127.0.0.1::5432 \
  --env POSTGRES_DB=payment_e2e \
  --env POSTGRES_USER=payment_e2e \
  --env POSTGRES_PASSWORD=payment_e2e \
  postgres:17.4 >/dev/null

for _ in {1..60}; do
  if docker exec "$POSTGRES_CONTAINER" pg_isready -U payment_e2e -d payment_e2e >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "$POSTGRES_CONTAINER" pg_isready -U payment_e2e -d payment_e2e >/dev/null
POSTGRES_PORT="$(docker port "$POSTGRES_CONTAINER" 5432/tcp | sed -nE 's#^.*:([0-9]+)$#\1#p' | tail -n 1)"
test -n "$POSTGRES_PORT"

(
  cd "$ROOT_DIR/backend"
  CORS_ALLOWED_ORIGINS="$FRONTEND_URL" \
  DEFAULT_ADMIN_EMAIL="$ADMIN_EMAIL" \
  DEFAULT_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  ./mvnw -DskipTests test-compile spring-boot:test-run \
    -Dspring-boot.run.main-class=com.agencia.pagos.payment.PaymentFullStackTestApplication \
    -Dspring-boot.run.arguments="--server.address=127.0.0.1 --server.port=$BACKEND_PORT --spring.datasource.url=jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/payment_e2e --spring.datasource.username=payment_e2e --spring.datasource.password=payment_e2e --spring.jpa.hibernate.ddl-auto=update --spring.jpa.open-in-view=false --jwt.access.secret=dGVzdC1zZWNyZXQtcGFyYS1jaS1vbmx5LXF1ZS1zZWEtbG8tc3VmaWNpZW50ZW1lbnRlLWxhcmdvLXBhcmEtaG1hYw== --app.frontend.url=$FRONTEND_URL --app.notifications.installments.enabled=false --app.storage.receipts.cleanup.enabled=false --payment.fx-test.call-log=$FX_CALL_LOG"
) >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!

auth_payload="{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
for _ in {1..120}; do
  if curl --silent --fail --max-time 3 \
      --header 'Content-Type: application/json' \
      --data "$auth_payload" \
      "$BACKEND_URL/api/v1/auth/token" >/dev/null 2>&1; then
    break
  fi
  kill -0 "$BACKEND_PID" 2>/dev/null || {
    printf 'Backend exited before readiness. Log:\n' >&2
    cat "$BACKEND_LOG" >&2
    exit 1
  }
  sleep 1
done
curl --silent --fail --max-time 3 \
  --header 'Content-Type: application/json' \
  --data "$auth_payload" \
  "$BACKEND_URL/api/v1/auth/token" >/dev/null

(
  cd "$ROOT_DIR/frontend"
  VITE_BASE_API_URL="$BACKEND_URL" npm run dev -- --host 127.0.0.1 --port "$FRONTEND_PORT" --strictPort
) >"$FRONTEND_LOG" 2>&1 &
FRONTEND_PID=$!

for _ in {1..60}; do
  if curl --silent --fail --max-time 3 "$FRONTEND_URL" >/dev/null 2>&1; then
    break
  fi
  kill -0 "$FRONTEND_PID" 2>/dev/null || {
    printf 'Frontend exited before readiness. Log:\n' >&2
    cat "$FRONTEND_LOG" >&2
    exit 1
  }
  sleep 1
done
curl --silent --fail --max-time 3 "$FRONTEND_URL" >/dev/null

(
  cd "$ROOT_DIR/frontend"
  PAYMENT_E2E_API_URL="$BACKEND_URL" \
  PAYMENT_E2E_FRONTEND_URL="$FRONTEND_URL" \
  PAYMENT_E2E_FX_CALL_LOG="$FX_CALL_LOG" \
  PAYMENT_E2E_ADMIN_EMAIL="$ADMIN_EMAIL" \
  PAYMENT_E2E_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  npx --no-install playwright test --config=playwright.config.ts
)
