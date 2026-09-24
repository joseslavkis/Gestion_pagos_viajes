#!/usr/bin/env bash
#
# Cross-platform (macOS + Ubuntu + any Docker Compose v2+ host) E2E smoke
# test for the payment-locking fix.
#
# Behavior:
#   * Generates test-only credentials and resets every isolation-critical variable inside the script
#     (storage path, bind hosts, published ports, Compose project name) so
#     the caller's environment cannot redirect the run to dev/VPS storage
#     or to a public interface.
#   * Avoids host-port races by publishing the backend on 127.0.0.1:0
#     (Docker assigns a free port). The backend endpoint is
#     discovered through `docker compose port backend 8080`; the result is
#     parsed defensively for macOS ([::]:PORT) and Ubuntu (0.0.0.0:PORT
#     / 127.0.0.1:PORT) output formats.
#   * PostgreSQL storage is a project-scoped Docker-managed named volume in an
#     ephemeral test-only Compose file. The runtime never reads the repository
#     .env or root Compose file and never touches a host database path.
#   * DB readiness is checked via `docker compose exec db pg_isready`, NOT
#     via a host-side port (no Postgres connect to the host needed).
#   * Removes only the built ${PROJECT}-backend image on exit; never prunes
#     globally.
#   * Coordinated simultaneous HTTP launch is the documented E2E limitation.

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- Force-set isolation-critical vars INSIDE the script ---------------
# Plain assignment (NOT `export`) overrides inherited environment for this
# shell and any spawned docker compose process. These cannot leak from the
# caller's shell: if the caller had VOLUME_DIR=. or BACKEND_BIND_HOST=0.0.0.0,
# both are now overridden.
#
# PROJECT collision-safety:
#   * mktemp -d "<TMPDIR-or-/tmp>/payment-concurrency-e2e.XXXXXX" is the
#     form accepted by BOTH BSD/macOS and GNU/Ubuntu mktemp. POSIX mktemp
#     guarantees there is no TOCTOU race (the XXXXXX suffix is replaced
#     atomically).
#   * The PROJECT name is derived from that directory's basename (sanitized
#     to lowercase alphanumeric + hyphens) AND the current PID. Two near-
#     simultaneous invocations cannot collide.
PROJECT_BASE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/payment-concurrency-e2e.XXXXXX")"
SAFE_BASE="$(printf '%s' "$(basename "$PROJECT_BASE_DIR")" | tr -dc 'a-z0-9-' | head -c 48)"
PROJECT="e2e-${SAFE_BASE}-pid$$"
TMP_DIR="$PROJECT_BASE_DIR"
ENV_FILE="$TMP_DIR/.env"
COMPOSE_OVERRIDE_FILE="$TMP_DIR/compose.override.yml"
COMPOSE=()
ACTIVE_PIDS=()

# Bind hosts -- caller provides plain "127.0.0.1" (no trailing colon;
# docker-compose.yml adds the separator). Compose appends ":<hostport>"
# only when the variable is non-empty; default Ubuntu/VPS production
# rendering (empty -> no IP prefix -> all-interfaces bind) is unchanged.
BACKEND_BIND_HOST="127.0.0.1"
FRONTEND_BIND_HOST="127.0.0.1"

# Published host ports for ALL services used by the E2E. Ask Docker for a
# free port (0 -> ephemeral). This eliminates host-port collisions across
# concurrent invocations on the same machine.
DB_EXTERNAL_PORT="0"
BACKEND_EXTERNAL_PORT="0"
FRONTEND_EXTERNAL_PORT="0"
ADMINER_EXTERNAL_PORT="0"

# EPHEMERAL test-only Compose file. Storage path & volume name are NOT
# caller-controlled and no production/development service is targeted.
# The volume short name is hardcoded; Compose prepends "${PROJECT}_" so
# the realized full name is e.g.
#   payment-concurrency-e2e-foo-pid1234_payment-concurrency-pgdata
# `docker compose down --volumes` removes it automatically.
cat >"$COMPOSE_OVERRIDE_FILE" <<OVERRIDE
services:
  db:
    image: "postgres:17.4"
    environment:
      POSTGRES_DB: "payment_concurrency"
      POSTGRES_USER: "payment_concurrency"
      POSTGRES_PASSWORD: "payment-concurrency-only"
    volumes:
      - "payment-concurrency-pgdata:/var/lib/postgresql/data"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U payment_concurrency -d payment_concurrency"]
      interval: 2s
      timeout: 3s
      retries: 30
  backend:
    build: "$ROOT_DIR/backend"
    env_file:
      - "$ENV_FILE"
    depends_on:
      db:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: "jdbc:postgresql://db:5432/payment_concurrency"
      SPRING_DATASOURCE_USERNAME: "payment_concurrency"
      SPRING_DATASOURCE_PASSWORD: "payment-concurrency-only"
      SPRING_PROFILES_ACTIVE: "local"
    ports:
      - "127.0.0.1::8080"
volumes:
  payment-concurrency-pgdata:
OVERRIDE
chmod 600 "$COMPOSE_OVERRIDE_FILE"

cleanup() {
  if ((${#ACTIVE_PIDS[@]} > 0)); then
    for pid in "${ACTIVE_PIDS[@]}"; do
      kill "$pid" 2>/dev/null || true
    done
    for pid in "${ACTIVE_PIDS[@]}"; do
      wait "$pid" 2>/dev/null || true
    done
  fi
  if ((${#COMPOSE[@]} > 0)); then
    "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
    # Remove only the locally built image for this isolated project so the
    # runner does not accumulate `${PROJECT}-backend` images after aborted or
    # partial runs. Pruning globally is intentionally avoided.
    docker image rm "${PROJECT}-backend:latest" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

# Note: VOLUME_DIR is intentionally NOT written here. The base compose file
# references ${VOLUME_DIR}/data/postgres for production / VPS use; the E2E
# override above REPLACES that volume entirely with a project-scoped named
# volume, so a caller-provided VOLUME_DIR is irrelevant and cannot redirect
# storage.
{
  printf 'DB_NAME=payment_concurrency\n'
  printf 'DB_USERNAME=payment_concurrency\n'
  printf 'DB_PASSWORD=payment-concurrency-only\n'
  printf 'VOLUME_DIR=%s/unused\n' "$TMP_DIR"
  printf 'JWT_ACCESS_SECRET=dGVzdC1zZWNyZXQtcGFyYS1jaS1vbmx5LXF1ZS1zZWEtbG8tc3VmaWNpZW50ZW1lbnRlLWxhcmdvLXBhcmEtaG1hYw==\n'
  printf 'DEFAULT_ADMIN_EMAIL=payment-concurrency-admin@example.com\n'
  printf 'DEFAULT_ADMIN_PASSWORD=Payment-Concurrency-Admin-2026!\n'
  printf 'DB_EXTERNAL_PORT=%s\n' "$DB_EXTERNAL_PORT"
  printf 'BACKEND_EXTERNAL_PORT=%s\n' "$BACKEND_EXTERNAL_PORT"
  printf 'FRONTEND_EXTERNAL_PORT=%s\n' "$FRONTEND_EXTERNAL_PORT"
  printf 'ADMINER_EXTERNAL_PORT=%s\n' "$ADMINER_EXTERNAL_PORT"
  printf 'BACKEND_BIND_HOST=%s\n' "$BACKEND_BIND_HOST"
  printf 'FRONTEND_BIND_HOST=%s\n' "$FRONTEND_BIND_HOST"
  printf 'INSTALLMENT_NOTIFICATIONS_ENABLED=false\n'
  printf 'INSTALLMENT_NOTIFICATIONS_CRON=0 0 9 * * *\n'
  printf 'INSTALLMENT_NOTIFICATIONS_ZONE=America/Argentina/Buenos_Aires\n'
  printf 'RECEIPTS_CLEANUP_ENABLED=false\n'
  printf 'SMTP_HOST=\n'
  printf 'SMTP_PORT=587\n'
  printf 'SMTP_USERNAME=\n'
  printf 'SMTP_APP_PASSWORD=\n'
  printf 'BREVO_API_KEY=\n'
  printf 'BREVO_FROM_EMAIL=\n'
  printf 'BREVO_REPLY_TO=\n'
  printf 'SMTP_FROM=\n'
  printf 'SMTP_KEY=\n'
  printf 'QUERY_MAIL=\n'
  printf 'APP_MAIL_TO=\n'
  printf 'BREVO_FROM_NAME=Payment Concurrency Test\n'
  printf 'FRONTEND_URL=http://127.0.0.1\n'
  printf 'BACKEND_EXTERNAL_URL=http://127.0.0.1\n'
  printf 'CORS_ALLOWED_ORIGINS=http://127.0.0.1\n'
  printf 'RECEIPTS_STORAGE_PROVIDER=inline\n'
} >>"$ENV_FILE"
chmod 600 "$ENV_FILE"

COMPOSE=(docker compose --project-name "$PROJECT" --env-file "$ENV_FILE" \
  --file "$COMPOSE_OVERRIDE_FILE")

# Discover the assigned host port for the backend. Docker Compose's `port`
# command prints formats that vary by host: `127.0.0.1:32768` (Ubuntu /
# when host_ip is set), `0.0.0.0:32768` (when no host_ip), or `[::]:32768`
# (macOS Docker Desktop binds to dual-stack by default). We extract just
# the final numeric port.
discover_port() {
  local service="$1" container_port="$2"
  local raw
  raw="$("${COMPOSE[@]}" port "$service" "$container_port" 2>/dev/null || true)"
  # Strip an optional bracketed IPv6 host: "[::]:32768" -> ":32768" -> "32768"
  # Strip a plain IPv4 host:    "0.0.0.0:32768" -> "32768"
  # Defensive regex matches optional "[host]" followed by ":digits".
  local port
  port="$(printf '%s' "$raw" | sed -nE 's#^(.*:)?([0-9]+)$#\2#p' | tail -n 1)"
  if [[ -z "$port" ]]; then
    printf 'Could not discover %s:%s (docker compose port returned %q)\n' "$service" "$container_port" "$raw" >&2
    return 1
  fi
  printf '%s\n' "$port"
}

printf 'Starting isolated PostgreSQL and backend services...\n'
"${COMPOSE[@]}" up -d --build db >/dev/null

# Extract identity values from the isolated env-file (NOT the parent shell).
DB_USERNAME="$(grep '^DB_USERNAME=' "$ENV_FILE" | tail -n 1 | cut -d= -f2)"
DB_NAME="$(grep '^DB_NAME=' "$ENV_FILE" | tail -n 1 | cut -d= -f2)"
ADMIN_EMAIL="$(grep '^DEFAULT_ADMIN_EMAIL=' "$ENV_FILE" | tail -n 1 | cut -d= -f2)"
ADMIN_PASSWORD="$(grep '^DEFAULT_ADMIN_PASSWORD=' "$ENV_FILE" | tail -n 1 | cut -d= -f2)"

# DB readiness check uses container-level `pg_isready`. No host DB port is
# touched, so even if Compose assigns 0 (which it usually maps to nothing
# useful for our purposes, we don't care).
for _ in {1..60}; do
  if "${COMPOSE[@]}" exec -T db pg_isready -U "$DB_USERNAME" -d "$DB_NAME" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
"${COMPOSE[@]}" exec -T db pg_isready -U "$DB_USERNAME" -d "$DB_NAME" >/dev/null
"${COMPOSE[@]}" up -d --build backend >/dev/null

# Discover backend host port (Docker assigned one when we published 0).
BACKEND_HOST_PORT="$(discover_port backend 8080)"
BASE_URL="http://127.0.0.1:${BACKEND_HOST_PORT}"

# Sanity: confirm the env file also shows the values we expect.
if [[ -z "$BACKEND_HOST_PORT" || "$BACKEND_HOST_PORT" -lt 1 ]]; then
  printf 'Backend port discovery did not yield a usable number: %q\n' "$BACKEND_HOST_PORT" >&2
  exit 1
fi

write_curl_config() {
  local path="$1" method="$2" url="$3" token="${4:-}"
  {
    printf 'request = "%s"\n' "$method"
    printf 'url = "%s"\n' "$url"
    printf 'connect-timeout = 5\nmax-time = 30\nsilent\nshow-error\n'
    printf 'header = "Content-Type: application/json"\n'
    [[ -n "$token" ]] && printf 'header = "Authorization: Bearer %s"\n' "$token"
  } >"$path"
  chmod 600 "$path"
}

request() {
  local name="$1" method="$2" url="$3" token="$4" body="$5"
  local config="$TMP_DIR/$name.curl"
  write_curl_config "$config" "$method" "$BASE_URL$url" "$token"
  printf '%s' "$body" | curl --config "$config" -o "$TMP_DIR/$name.body" -w '%{http_code}' --data-binary @-
}

get_json() {
  local name="$1" url="$2" token="$3"
  local config="$TMP_DIR/$name.curl"
  write_curl_config "$config" GET "$BASE_URL$url" "$token"
  curl --config "$config" -o "$TMP_DIR/$name.body" -w '%{http_code}'
}

wait_for_workers() {
  local first_pid="$1" second_pid="$2" first_label="$3" second_label="$4"
  local first_status=0 second_status=0
  if wait "$first_pid"; then :; else first_status=$?; fi
  if wait "$second_pid"; then :; else second_status=$?; fi
  remove_pid "$first_pid"
  remove_pid "$second_pid"
  if ((first_status != 0 || second_status != 0)); then
    printf '%s/%s worker failed\n' "$first_label" "$second_label" >&2
    return 1
  fi
}

track_pid() {
  ACTIVE_PIDS+=("$1")
}

remove_pid() {
  local target="$1" pid
  local remaining=()
  for pid in "${ACTIVE_PIDS[@]}"; do
    [[ "$pid" == "$target" ]] || remaining+=("$pid")
  done
  if ((${#remaining[@]} > 0)); then
    ACTIVE_PIDS=("${remaining[@]}")
  else
    ACTIVE_PIDS=()
  fi
}

wait_for_ready_files() {
  local first="$1" second="$2"
  for _ in {1..100}; do
    if [[ -f "$first" && -f "$second" ]]; then
      return
    fi
    sleep 0.1
  done
  printf 'Concurrent workers did not reach the start barrier\n' >&2
  return 1
}

printf '%s' "$ADMIN_PASSWORD" >"$TMP_DIR/admin.password"
chmod 600 "$TMP_DIR/admin.password"
auth_ready_body="$(jq -nc --arg email "$ADMIN_EMAIL" --rawfile password "$TMP_DIR/admin.password" '{email:$email,password:$password}')"
for _ in {1..90}; do
  status="$(request auth-ready POST /api/v1/auth/token '' "$auth_ready_body" || true)"
  if [[ "$status" == 200 ]]; then
    break
  fi
  sleep 2
done
[[ "$status" == 200 ]] || { printf 'Authentication readiness failed (HTTP %s)\n' "$status" >&2; exit 1; }
admin_token="$(jq -er '.accessToken' "$TMP_DIR/auth-ready.body")"

assert_status() {
  local name="$1" expected="$2" actual="$3"
  [[ "$actual" == "$expected" ]] || {
    printf '%s failed (HTTP %s)\n' "$name" "$actual" >&2
    exit 1
  }
}

stamp="$(date +%s)-$RANDOM"
historical_payment_date="2020-01-15"
user_email="payment-concurrency-$stamp@example.com"
user_password="Concurrency-${stamp}-${RANDOM}!"
student_dni="$(printf '%08d' $((RANDOM * 100 + RANDOM)))"

trip_body="$(jq -nc --arg name "Concurrency trip $stamp" \
  '{name:$name,totalAmount:1000,firstInstallmentAmount:1000,installmentsCount:1,dueDay:10,yellowWarningDays:5,retroactiveActive:false,currency:"ARS",firstDueDate:(now|strftime("%Y-%m-%d"))}')"
trip_status="$(request trip POST /api/v1/trips "$admin_token" "$trip_body")"
assert_status trip 201 "$trip_status"
trip_id="$(jq -er '.id' "$TMP_DIR/trip.body")"
assign_body="$(jq -nc --arg dni "$student_dni" '{studentDnis:[$dni]}')"
assign_status="$(request assign POST "/api/v1/trips/$trip_id/users/bulk" "$admin_token" "$assign_body")"
assert_status assign 200 "$assign_status"

printf '%s' "$user_password" >"$TMP_DIR/user.password"
chmod 600 "$TMP_DIR/user.password"
signup_body="$(jq -nc --arg email "$user_email" --rawfile password "$TMP_DIR/user.password" --arg dni "$student_dni" \
  '{email:$email,password:$password,name:"Concurrency",lastname:"Tester",dni:$dni,phone:"123456789",students:[{name:"Concurrency",lastname:"Student",dni:$dni}]}')"
signup_status="$(request signup POST /api/v1/auth/signup '' "$signup_body")"
assert_status signup 201 "$signup_status"
user_token="$(jq -er '.accessToken' "$TMP_DIR/signup.body")"

bank_body="$(jq -nc --arg suffix "$stamp" \
  '{bankName:"Concurrency Bank",accountLabel:"ARS account",accountHolder:"Concurrency Tester",accountNumber:("0001-"+$suffix),taxId:"30-71131646-5",cbu:("0000000000000000000000"+$suffix),alias:("CONC."+$suffix),currency:"ARS",displayOrder:0}')"
bank_status="$(request bank POST /api/v1/bank-accounts "$admin_token" "$bank_body")"
assert_status bank 201 "$bank_status"
bank_id="$(jq -er '.id' "$TMP_DIR/bank.body")"

installments_status="$(get_json installments /api/v1/payments/my/installments "$user_token")"
assert_status installments 200 "$installments_status"
cp "$TMP_DIR/installments.body" "$TMP_DIR/installments.json"
installment_id="$(jq -er --argjson trip "$trip_id" '.[] | select(.tripId == $trip) | .installmentId' "$TMP_DIR/installments.json" | head -n 1)"
register_body="$(jq -nc --argjson installment "$installment_id" --argjson bank "$bank_id" \
  --arg payment_date "$historical_payment_date" \
  '{anchorInstallmentId:$installment,reportedAmount:100,reportedPaymentDate:$payment_date,paymentCurrency:"ARS",paymentMethod:"BANK_TRANSFER",bankAccountId:$bank}')"
register_status="$(request register POST /api/v1/payments "$user_token" "$register_body")"
assert_status register 201 "$register_status"
submission_id="$(jq -er '.submissionId' "$TMP_DIR/register.body")"

admin_config="$TMP_DIR/admin.curl"
user_config="$TMP_DIR/user.curl"
write_curl_config "$admin_config" PATCH "$BASE_URL/api/v1/payments/$submission_id/review" "$admin_token"
write_curl_config "$user_config" GET "$BASE_URL/api/v1/payments/my/installments" "$user_token"
review_body='{"approvedAmount":100}'
review_body_file="$TMP_DIR/review.json"
printf '%s' "$review_body" >"$review_body_file"
chmod 600 "$review_body_file"
review_gate="$TMP_DIR/review.gate"

(
  : >"$TMP_DIR/review-1.ready"
  gate_deadline=$((SECONDS + 15))
  while [[ ! -f "$review_gate" ]]; do
    ((SECONDS >= gate_deadline)) && exit 124
    sleep 0.1
  done
  exec curl --config "$admin_config" --request PATCH \
    --url "$BASE_URL/api/v1/payments/$submission_id/review" \
    -o "$TMP_DIR/review-1.body" -w '%{http_code}' --data-binary "@$review_body_file" >"$TMP_DIR/review-1.status"
) & review_one=$!
(
  : >"$TMP_DIR/review-2.ready"
  gate_deadline=$((SECONDS + 15))
  while [[ ! -f "$review_gate" ]]; do
    ((SECONDS >= gate_deadline)) && exit 124
    sleep 0.1
  done
  exec curl --config "$admin_config" --request PATCH \
    --url "$BASE_URL/api/v1/payments/$submission_id/review" \
    -o "$TMP_DIR/review-2.body" -w '%{http_code}' --data-binary "@$review_body_file" >"$TMP_DIR/review-2.status"
) & review_two=$!
track_pid "$review_one"
track_pid "$review_two"
wait_for_ready_files "$TMP_DIR/review-1.ready" "$TMP_DIR/review-2.ready"
: >"$review_gate"
wait_for_workers "$review_one" "$review_two" REVIEW-1 REVIEW-2

review_success=0
review_conflict=0
for n in 1 2; do
  code="$(<"$TMP_DIR/review-$n.status")"
   if [[ "$code" == *200* ]] && jq -e --argjson id "$submission_id" '.submissionId == $id and .status == "APPROVED"' "$TMP_DIR/review-$n.body" >/dev/null; then
    review_success=$((review_success + 1))
  elif [[ "$code" == *409* && "$(<"$TMP_DIR/review-$n.body")" == "Este pago ya fue revisado" ]]; then
    review_conflict=$((review_conflict + 1))
  fi
done
paid_json="$(curl --config "$user_config" --url "$BASE_URL/api/v1/payments/my/installments")"
credit="$(printf '%s' "$paid_json" | jq -er --argjson installment "$installment_id" '.[] | select(.installmentId == $installment) | .paidAmount' | head -n 1 | jq -R 'tonumber')"
credit_is_expected="$(jq -nr --arg value "$credit" '$value | tonumber == 100')"
[[ "$review_success" == 1 && "$review_conflict" == 1 && "$credit_is_expected" == true ]] || { printf 'REVIEW failed: success=%s conflict=%s credit=%s\n' "$review_success" "$review_conflict" "$credit"; exit 1; }
printf 'REVIEW: exactly one success and one already-processed conflict; credit=%s\n' "$credit"

write_curl_config "$admin_config" POST "$BASE_URL/api/v1/payments/$submission_id/void" "$admin_token"
void_gate="$TMP_DIR/void.gate"
(
  : >"$TMP_DIR/void-1.ready"
  gate_deadline=$((SECONDS + 15))
  while [[ ! -f "$void_gate" ]]; do
    ((SECONDS >= gate_deadline)) && exit 124
    sleep 0.1
  done
  exec curl --config "$admin_config" --request POST --url "$BASE_URL/api/v1/payments/$submission_id/void" \
    -o "$TMP_DIR/void-1.body" -w '%{http_code}' >"$TMP_DIR/void-1.status"
) & void_one=$!
(
  : >"$TMP_DIR/void-2.ready"
  gate_deadline=$((SECONDS + 15))
  while [[ ! -f "$void_gate" ]]; do
    ((SECONDS >= gate_deadline)) && exit 124
    sleep 0.1
  done
  exec curl --config "$admin_config" --request POST --url "$BASE_URL/api/v1/payments/$submission_id/void" \
    -o "$TMP_DIR/void-2.body" -w '%{http_code}' >"$TMP_DIR/void-2.status"
) & void_two=$!
track_pid "$void_one"
track_pid "$void_two"
wait_for_ready_files "$TMP_DIR/void-1.ready" "$TMP_DIR/void-2.ready"
: >"$void_gate"
wait_for_workers "$void_one" "$void_two" VOID-1 VOID-2

void_success=0
void_conflict=0
for n in 1 2; do
  code="$(<"$TMP_DIR/void-$n.status")"
  if [[ "$code" == *200* ]] && jq -e --argjson id "$submission_id" '.submissionId == $id and .status == "VOIDED"' "$TMP_DIR/void-$n.body" >/dev/null; then
    void_success=$((void_success + 1))
  elif [[ "$code" == *409* && "$(<"$TMP_DIR/void-$n.body")" == "Este pago ya fue anulado" ]]; then
    void_conflict=$((void_conflict + 1))
  fi
done
paid_json="$(curl --config "$user_config" --url "$BASE_URL/api/v1/payments/my/installments")"
reversal="$(printf '%s' "$paid_json" | jq -er --argjson installment "$installment_id" '.[] | select(.installmentId == $installment) | .paidAmount' | head -n 1 | jq -R 'tonumber')"
reversal_is_expected="$(jq -nr --arg value "$reversal" '$value | tonumber == 0')"
[[ "$void_success" == 1 && "$void_conflict" == 1 && "$reversal_is_expected" == true ]] || { printf 'VOID failed: success=%s conflict=%s paid=%s\n' "$void_success" "$void_conflict" "$reversal"; exit 1; }
printf 'VOID: exactly one success and one already-voided conflict; paid=%s (reversed once)\n' "$reversal"

printf 'Stopping isolated Compose stack before Testcontainers cross-currency checks...\n'
"${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null
docker image rm "${PROJECT}-backend:latest" >/dev/null 2>&1 || true
COMPOSE=()

printf 'Running cross-currency concurrent review/void checks against disposable PostgreSQL...\n'
(
  cd "$ROOT_DIR/backend"
  ./mvnw -Dtest='ConcurrentFinancialIntegrityIntegrationTest#crossCurrencyConcurrentReview_conservesAmountsAndOnlyApprovesOnce+crossCurrencyConcurrentVoid_reversesPersistedAllocationAndOnlyVoidsOnce' test
)
