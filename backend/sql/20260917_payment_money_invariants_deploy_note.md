# Payment money invariants migration — manual rollout

Production schema changes remain manual. Hibernate must validate the migrated schema; it must not create or rewrite these columns in production.

## Required order

The rollout sequence is **SQL → backend → frontend**.

1. Back up the database and verify the backup before changing the schema.
2. Apply `backend/sql/20260917_payment_money_invariants.sql` with `ON_ERROR_STOP=1` before deploying the compatible backend.
3. Run the verification queries below. Stop the rollout if any result differs from the documented expectation.
4. Deploy the backend with `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` and verify the exact image SHA/version that passed CI.
5. Deploy the frontend only after the compatible backend is healthy.
6. Exercise same-currency registration and cross-currency preview, registration, review, history, and void smoke paths.
7. Retire the previous calculation path only after the 300-second preview-token window has elapsed and no pre-change token can be submitted.

Do not use `ddl-auto=update` as a substitute for this migration.

## Apply

```sh
psql "$DB_URL" -v ON_ERROR_STOP=1 \
  -f backend/sql/20260917_payment_money_invariants.sql
```

The migration is idempotent. A rerun leaves values unchanged. It widens nullable `exchange_rate` to `NUMERIC(18,8)`, adds nullable snapshot columns, labels pre-change rows as `v1`, and backfills provider only from a non-NULL persisted source. It does not invent a same-currency rate or reconstruct lost precision.

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

Expected: `exchange_rate` is nullable `NUMERIC(18,8)` and all three snapshot columns exist.

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

Expected: every count is `0`. These checks do not rewrite approved, resolved, or voided history.

## Legacy pending submissions

Rows labeled `v1` retain the persisted scale-2 rate. Review never calls an exchange-rate provider and never reconstructs lost precision. Approval is recorded as an explicit legacy reconciliation; rejection remains explicit through the reviewer observation.

## Rollback

The schema widening is backward-compatible and must remain in place. Do not shrink or drop snapshot columns, recalculate rates, or rewrite historical outcomes. If the backend must be rolled back, suspend cross-currency payments until a compatible version is restored. Same-currency rows keep a NULL rate.

## Non-goals

- No Flyway or Liquibase adoption.
- No automatic revaluation of approved, resolved, or voided submissions.
- No credit or overpayment ledger.
- No production execution as part of this change.
