# Payment money invariants migration — manual rollout

Production schema changes remain manual. Hibernate must validate the migrated schema; it must not create or rewrite these columns in production.

## Required rollout order

The rollout sequence is **SQL → backend → frontend**.

1. Announce a maintenance window. Freeze payment registration, review, and void writes across every backend instance; drain in-flight payment transactions. Pause Vercel production promotion.
2. Create a database backup and verify it by restoring it to a disposable database before changing the schema.
3. Apply the additive migration manually, then run the read-only verification queries below. Do not proceed if any result differs from the documented expectation.
4. Run `./scripts/check-payment-schema-readiness.sh` from the Compose application directory. It performs read-only checks through the real `db` service and fails closed before backend startup when the schema is incompatible.
5. Deploy the exact SHA validated by CI with the explicit `production` profile and Hibernate `validate`. Verify the deployed SHA and backend health before continuing.
6. Only after the compatible backend is healthy, promote the intended frontend release from Vercel. Run same-currency and cross-currency preview, registration, review, history/saldo, and void smoke flows.
7. Restore payment writes after all smoke checks pass. Keep Vercel production promotion paused until step 6.

Do not use `ddl-auto=update` as a substitute for this migration.
Do not apply this production migration automatically at application startup or from the CI deploy workflow.

## Apply

For a local or explicitly disposable database managed by this Compose project, stream the SQL into the actual Compose `db` container. This command is not a production command or authorization to run the migration:

```sh
docker compose exec -T db sh -c \
  'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < backend/sql/20260917_payment_money_invariants.sql
```

Production application remains a separate, manually approved operator action. The command reads credentials inside the container and does not print them.

The migration is transactional and idempotent. A rerun leaves values unchanged. It widens nullable `exchange_rate` to `NUMERIC(18,8)`, adds nullable snapshot columns, backfills pre-change rows as `v1`, then enforces `DEFAULT 'v1'` and `NOT NULL` for legacy inserts that omit `calculation_version`. Explicit `NULL` is rejected. Provider identity is backfilled only from a non-NULL persisted source. The migration does not invent a same-currency rate or reconstruct lost precision.

## Verify

```sql
SELECT column_name, data_type, numeric_precision, numeric_scale, is_nullable
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name = 'payment_submissions'
  AND column_name IN (
      'exchange_rate',
      'exchange_rate_scale',
      'exchange_rate_provider',
      'calculation_version'
  )
ORDER BY column_name;
```

Expected: `exchange_rate` is nullable `NUMERIC(18,8)`; `exchange_rate_scale` and `exchange_rate_provider` exist; and `calculation_version` is `NOT NULL` with default `v1`.

```sql
SELECT COUNT(*) AS invalid_provider_backfills
FROM payment_submissions
WHERE exchange_rate_source IS NULL
  AND exchange_rate_provider IS NOT NULL;

SELECT COUNT(*) AS unlabeled_legacy_rows
FROM payment_submissions
WHERE calculation_version IS NULL;

SELECT COUNT(*) AS invented_same_currency_rates
FROM payment_submissions
WHERE exchange_rate IS NOT NULL
  AND exchange_rate_scale IS NULL;
```

Expected: every count is `0`. These are audit-only queries; never run corrective `UPDATE` statements against historical submissions, outcomes, allocations, or installment balances.

The schema preflight command uses read-only `SELECT` queries, verifies the migration columns, numeric scale, default, nullability, and scale constraint, and returns a failure before the workflow runs `docker compose up -d --build backend`. It never applies SQL.

## Legacy pending submissions

Rows labeled `v1` retain the persisted scale-2 rate. Review never calls an exchange-rate provider and never reconstructs lost precision. Approval is recorded as an explicit legacy reconciliation; rejection remains explicit through the reviewer observation. New preview tokens use calculation version `3`; every earlier `cv=2` token is invalid immediately, so users must recalculate rather than waiting for the old token TTL.

For partial cross-currency review, the rejected trip-currency value is the residual share of the original persisted conversion snapshot. Approved and rejected outcomes conserve that original reported amount and trip-currency snapshot; do not treat the rejected portion as an independent conversion at a new rate.

## Rollback

The schema widening and new `calculation_version` default/constraint must remain in place. Rollback reverts application artifacts only: do not shrink or drop snapshot columns, remove the default/constraint, recalculate rates, issue corrective historical updates, or rewrite outcomes. If the backend must be rolled back, suspend cross-currency payments until a compatible version is restored. Same-currency rows keep a NULL rate.

## Non-goals

- No Flyway or Liquibase adoption.
- No automatic revaluation of approved, resolved, or voided submissions.
- No credit or overpayment ledger.
- No production execution as part of this change.
