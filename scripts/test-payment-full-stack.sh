#!/usr/bin/env bash

# Real PR2 backend, disposable PostgreSQL with PR1 migration, and Chromium.
# The deterministic FX implementation is compiled only in this temporary run.
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/payment-full-stack.XXXXXX")"
POSTGRES_CONTAINER="payment-full-stack-$PPID-$$"
BACKEND_PID=""
FRONTEND_PID=""
FX_CALL_LOG="$TMP_DIR/fx-calls.log"

cleanup() {
  [[ -z "$FRONTEND_PID" ]] || kill "$FRONTEND_PID" 2>/dev/null || true
  [[ -z "$BACKEND_PID" ]] || kill "$BACKEND_PID" 2>/dev/null || true
  [[ -z "$FRONTEND_PID" ]] || wait "$FRONTEND_PID" 2>/dev/null || true
  [[ -z "$BACKEND_PID" ]] || wait "$BACKEND_PID" 2>/dev/null || true
  docker rm -f "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

free_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

command -v docker >/dev/null
command -v curl >/dev/null
command -v python3 >/dev/null
command -v javac >/dev/null
(
  cd "$ROOT_DIR/frontend"
  node -e "require.resolve('@playwright/test/package.json')"
) >/dev/null 2>&1 || {
  printf 'Pinned Playwright is unavailable; run npm ci in frontend first.\n' >&2
  exit 2
}

BACKEND_PORT="$(free_port)"
FRONTEND_PORT="$(free_port)"
FRONTEND_URL="http://127.0.0.1:$FRONTEND_PORT"
BACKEND_URL="http://127.0.0.1:$BACKEND_PORT"
ADMIN_EMAIL="payment-e2e-admin@example.com"
ADMIN_PASSWORD="Payment-E2e-Admin-2026!"
JWT_SECRET="dGVzdC1zZWNyZXQtcGFyYS1jaS1vbmx5LXF1ZS1zZWEtbG8tc3VmaWNpZW50ZW1lbnRlLWxhcmdvLXBhcmEtaG1hYw=="

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
POSTGRES_PORT="$(docker port "$POSTGRES_CONTAINER" 5432/tcp)"
POSTGRES_PORT="${POSTGRES_PORT##*:}"
[[ "$POSTGRES_PORT" =~ ^[0-9]+$ ]]

(
  cd "$ROOT_DIR/backend"
  ./mvnw -q -Dmaven.test.skip=true package dependency:build-classpath \
    -DincludeScope=runtime -Dmdep.outputFile="$TMP_DIR/runtime-classpath"
)
RUNTIME_CP="$ROOT_DIR/backend/target/classes:$(<"$TMP_DIR/runtime-classpath")"

# Bootstrap only the empty disposable database using Hibernate, then apply the
# actual PR1 migration. All payment E2E requests use the restarted validate app.
start_backend() {
  local main_class="$1" ddl_mode="$2"
  shift 2
  CORS_ALLOWED_ORIGINS="$FRONTEND_URL" \
  DEFAULT_ADMIN_EMAIL="$ADMIN_EMAIL" \
  DEFAULT_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  java -cp "$TMP_DIR/classes:$RUNTIME_CP" "$main_class" \
    --server.address=127.0.0.1 \
    --server.port="$BACKEND_PORT" \
    --spring.datasource.url="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/payment_e2e" \
    --spring.datasource.username=payment_e2e \
    --spring.datasource.password=payment_e2e \
    --spring.jpa.hibernate.ddl-auto="$ddl_mode" \
    --spring.jpa.open-in-view=false \
    --jwt.access.secret="$JWT_SECRET" \
    --app.frontend.url="$FRONTEND_URL" \
    --app.notifications.installments.enabled=false \
    --app.storage.receipts.cleanup.enabled=false \
    --payment.fx-test.call-log="$FX_CALL_LOG" "$@" >"$TMP_DIR/backend.log" 2>&1 &
  BACKEND_PID=$!
}

wait_backend() {
  local auth_payload
  auth_payload="{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
  for _ in {1..120}; do
    if curl --silent --fail --max-time 3 --header 'Content-Type: application/json' \
        --data "$auth_payload" "$BACKEND_URL/api/v1/auth/token" >/dev/null 2>&1; then
      return 0
    fi
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      printf 'Disposable backend exited during startup.\n' >&2
      return 1
    fi
    sleep 1
  done
  printf 'Disposable backend did not become ready.\n' >&2
  return 1
}

start_backend com.agencia.pagos.PagosApplication update
wait_backend
kill "$BACKEND_PID"
wait "$BACKEND_PID" 2>/dev/null || true
BACKEND_PID=""

docker exec -i "$POSTGRES_CONTAINER" psql -v ON_ERROR_STOP=1 -U payment_e2e -d payment_e2e \
  < "$ROOT_DIR/backend/sql/20260917_payment_money_invariants.sql" >/dev/null
READINESS="$(docker exec -i "$POSTGRES_CONTAINER" psql -At -v ON_ERROR_STOP=1 -U payment_e2e -d payment_e2e \
  < "$ROOT_DIR/backend/sql/payment_money_schema_readiness.sql")"
if [[ "$READINESS" != READY ]]; then
  printf 'PR1 schema readiness failed: %s\n' "$READINESS" >&2
  exit 1
fi
printf 'Disposable PostgreSQL PR1 migration: READY\n'

mkdir -p "$TMP_DIR/src/com/agencia/pagos/payment" "$TMP_DIR/classes"
cat >"$TMP_DIR/src/com/agencia/pagos/payment/PaymentE2eApplication.java" <<'JAVA'
package com.agencia.pagos.payment;

import com.agencia.pagos.PagosApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

public final class PaymentE2eApplication {
    public static void main(String[] args) {
        new SpringApplication(PagosApplication.class, FixedQuoteConfiguration.class).run(args);
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedQuoteConfiguration {
        @Bean
        @Primary
        ExchangeRateQuoteProvider fixedQuoteProvider(
                @org.springframework.beans.factory.annotation.Value("${payment.fx-test.call-log}") String logPath) {
            return requestedDate -> {
                try {
                    Files.writeString(Path.of(logPath), requestedDate + System.lineSeparator(),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    if (requestedDate.equals(LocalDate.of(2026, 1, 14))) {
                        Thread.sleep(400);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Deterministic quote interrupted", e);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Could not log deterministic quote", e);
                }
                BigDecimal rate = switch (requestedDate.toString()) {
                    case "2026-01-13" -> new BigDecimal("1015.50");
                    // 2026-01-16 divides ARS 240000.00 into USD 200.00 exactly,
                    // so the ARS -> USD direction is asserted without rounding.
                    case "2026-01-16" -> new BigDecimal("1200.00");
                    default -> new BigDecimal("1234.56");
                };
                return new ExchangeRateQuote(rate, requestedDate, requestedDate, "payment-fx-test",
                        "deterministic-local-provider", requestedDate + "T12:00:00Z");
            };
        }
    }
}
JAVA
javac -cp "$RUNTIME_CP" -d "$TMP_DIR/classes" \
  "$TMP_DIR/src/com/agencia/pagos/payment/PaymentE2eApplication.java"
: > "$FX_CALL_LOG"
start_backend com.agencia.pagos.payment.PaymentE2eApplication validate
wait_backend

(
  cd "$ROOT_DIR/frontend"
  VITE_BASE_API_URL="$BACKEND_URL" npm run dev -- --host 127.0.0.1 --port "$FRONTEND_PORT" --strictPort
) >"$TMP_DIR/frontend.log" 2>&1 &
FRONTEND_PID=$!
for _ in {1..60}; do
  if curl --silent --fail --max-time 3 "$FRONTEND_URL" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    printf 'Disposable Vite server exited during startup.\n' >&2
    exit 1
  fi
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
  PAYMENT_E2E_RESULTS_DIR="$TMP_DIR/playwright-results" \
  npx --no-install playwright test --config=playwright.config.ts "$@"
)
