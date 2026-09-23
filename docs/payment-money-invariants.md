# Operate the payment money invariants safely

The safe release order is **write freeze + verified backup → SQL migration → compatible backend → frontend promotion → smoke tests → restore writes**. Production migration execution remains a separate, manually approved operator action.

## Quick verification path

1. Freeze payment registration, review, and void writes on all application instances; drain active payment transactions and pause Vercel production promotion.
2. Back up the database and verify the backup by restoring it to a disposable database.
3. Apply `backend/sql/20260917_payment_money_invariants.sql` manually; verify its schema and historical values. A second migration run must be a no-op.
4. Run `./scripts/check-payment-schema-readiness.sh` before backend startup. It performs read-only checks against the Compose `db` service and fails closed if the schema is incompatible.
5. Deploy the exact CI-tested backend SHA with the explicit `production` profile and Hibernate `ddl-auto=validate`; verify SHA and health.
6. Promote the intended Vercel frontend release only after backend health is confirmed, then run same- and cross-currency lifecycle smoke flows.
7. Restore payment writes only after the smoke flows pass.

## Apply locally to a disposable Compose database

```sh
docker compose exec -T db sh -c \
  'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < backend/sql/20260917_payment_money_invariants.sql
```

This is a local/disposable Compose command, not a production command or authorization to run production SQL. Database credentials stay inside the container and are not printed. Production migration execution is a separate manual operator action.

## Verification layers

| Layer | Command | Boundary |
|---|---|---|
| Backend unit and PostgreSQL integration | `cd backend && ./mvnw test` | Testcontainers PostgreSQL only |
| Frontend component contracts | `cd frontend && NODE_OPTIONS=--no-experimental-webstorage npm test` | Vitest, Testing Library, and MSW |
| Frontend build and lint | `cd frontend && npm run build && npm run lint` | Static production build |
| Concurrent review and void | `./scripts/test-payment-concurrency.sh` | Isolated Compose project plus Testcontainers |
| CASE F/G browser flow | `./scripts/test-payment-full-stack.sh` | Loopback-only backend/frontend and disposable PostgreSQL |
| Production schema preflight | `./scripts/check-payment-schema-readiness.sh` | Read-only queries through the Compose `db` service; runs before backend container startup |
| Complete local sweep | `./scripts/test-payment-money-invariants.sh` | Runs every layer above |

The browser harness requires the pinned Playwright test package and Chromium runtime. It never reads the repository `.env`, never calls an external FX provider, binds only to `127.0.0.1`, and removes its PostgreSQL container on exit. Production preflight runs the shared read-only SQL query, requires the exact `DEFAULT 'v1'`, `NOT NULL`, and validated scale-range constraint definition, and rejects calculation-version `2` cross-currency rows missing their rate/scale/provider/date snapshot. It performs no `UPDATE` or migration execution and must pass before any backend/container mutation. The backend must start with the explicit `production` profile and Hibernate validation; local developer Compose can retain its `local` profile and update behavior.

## User behavior

- Manual input is preserved when a calculation fails or an older response arrives late.
- CASE F keeps the original `20000 ARS` intent across ARS → USD → ARS switches, including a delayed response.
- CASE G displays the backend-authoritative `10.16 ARS` result for a `0.01 USD` balance at `1015.50` and keeps `99.29` payable.
- CASE J keeps the rejected trip-currency result as the residual share of the original persisted conversion; it is not independently reconverted or credited, and void reverses the persisted approved allocations.
- Calculation responses name the validated first-installment balance `anchorRemainingAmount` and the enrollment sum `totalPendingAmountInTripCurrency`; only the backend determines either value.
- Submission stays blocked while calculation is pending, unavailable, expired, or associated with a different input currency.
- Every preview token without the required `cv=2` claim is invalid immediately; users recalculate before registration. Persisted legacy `v1` pending submissions remain reviewable from their stored rate/date snapshot without provider refetch.

## Rollout and rollback

- Keep production promotion paused until the migrated, exact-SHA backend is healthy; promote the compatible frontend afterward.
- Do not recalculate approved payment history or reconstruct lost legacy precision.
- Use read-only audit queries for historical checks. Do not apply corrective `UPDATE` statements to approved outcomes, allocations, or installment balances.
- Do not shrink `exchange_rate`, drop snapshot columns, or reverse the additive migration.
- If rollback is required, revert application artifacts only, keep the schema/default/constraint in place, and suspend cross-currency payments until a compatible service is restored.

## Non-goals

- No credit or overpayment ledger.
- No automatic rewrite or revaluation of approved, resolved, or voided payments.
- No generalized money framework outside the payment domain.
- No installment schedule or lock-order redesign.
- No production-reachable FX override, mutation endpoint, or deterministic provider.
