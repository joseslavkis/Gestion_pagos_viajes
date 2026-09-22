# Operate the payment money invariants safely

Apply the additive SQL migration before the compatible backend, then deploy the frontend only after the backend version is verified. The required order is **SQL → backend → frontend**.

## Quick verification path

1. Apply `backend/sql/20260917_payment_money_invariants.sql` to the target database twice; the second run must be a no-op.
2. Start the compatible backend with Hibernate validation and verify the exact image SHA/version.
3. Deploy the frontend only after backend calculation responses expose canonical decimal strings and calculation version `2`.
4. Run `./scripts/test-payment-money-invariants.sh` against local, disposable resources before release approval.

## Verification layers

| Layer | Command | Boundary |
|---|---|---|
| Backend unit and PostgreSQL integration | `cd backend && ./mvnw test` | Testcontainers PostgreSQL only |
| Frontend component contracts | `cd frontend && NODE_OPTIONS=--no-experimental-webstorage npm test` | Vitest, Testing Library, and MSW |
| Frontend build and lint | `cd frontend && npm run build && npm run lint` | Static production build |
| Concurrent review and void | `./scripts/test-payment-concurrency.sh` | Isolated Compose project plus Testcontainers |
| CASE F/G browser flow | `./scripts/test-payment-full-stack.sh` | Loopback-only backend/frontend and disposable PostgreSQL |
| Complete local sweep | `./scripts/test-payment-money-invariants.sh` | Runs every layer above |

The browser harness requires the pinned Playwright test package and Chromium runtime. It never reads the repository `.env`, never calls an external FX provider, binds only to `127.0.0.1`, and removes its PostgreSQL container on exit.

## User behavior

- Manual input is preserved when a calculation fails or an older response arrives late.
- CASE F keeps the original `20000 ARS` intent across ARS → USD → ARS switches, including a delayed response.
- CASE G displays the backend-authoritative `10.16 ARS` result for a `0.01 USD` balance at `1015.50` and keeps `99.29` payable.
- Submission stays blocked while calculation is pending, unavailable, expired, or associated with a different input currency.

## Rollout and rollback

- Keep the old calculation path only for the bounded token-expiry transition, then retire it after the 300-second window.
- Do not recalculate approved payment history or reconstruct lost legacy precision.
- Do not shrink `exchange_rate`, drop snapshot columns, or reverse the additive migration.
- If rollback is required, revert the application release and suspend cross-currency payments until compatible services return.

## Non-goals

- No credit or overpayment ledger.
- No automatic rewrite or revaluation of approved, resolved, or voided payments.
- No generalized money framework outside the payment domain.
- No installment schedule or lock-order redesign.
- No production-reachable FX override, mutation endpoint, or deterministic provider.
