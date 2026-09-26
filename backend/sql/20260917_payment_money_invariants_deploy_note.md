# Payment money schema: manual rollout

This database-only slice makes the existing backend compatible with the widened
rate column. It does **not** change payment calculations. Do not publish the
backend/frontend money behavior until this migration and preflight are complete.

## Order and safety

1. Confirm `backend/sql/20260608_add_exchange_rate_audit.sql` has already been
   applied: `payment_submissions` needs `exchange_rate_requested_date`,
   `exchange_rate_effective_date`, `exchange_rate_source`, and
   `exchange_rate_provider_timestamp`. The new migration fails with a named
   prerequisite error if any are missing. Do not assume Hibernate created them.
2. Schedule a maintenance window, stop payment registration/review/void writes
   on **all** application instances, and drain in-flight transactions. Take a
   backup and verify a restore on a disposable database.
3. Apply `backend/sql/20260917_payment_money_invariants.sql` manually on the
   approved database. Widening `NUMERIC(10,2)` to `NUMERIC(18,8)` acquires an
   `ACCESS EXCLUSIVE` table lock: it blocks reads and writes while held and
   can wait behind long-running transactions. Plan a write freeze, monitor
   blockers, and use an operator-approved lock/statement timeout. No fixed
   duration or online-migration guarantee is implied.
4. Run `backend/sql/payment_money_schema_readiness.sql`; its single row must
   read `READY`. Run the read-only `backend/sql/payment_money_financial_audit.sql`
   and investigate every result without rewriting approved history. Run
   `./scripts/check-payment-schema-readiness.sh` from the repository; this
   checks the Compose `db` service and never applies migrations.
5. Only after those checks, deploy the tested backend SHA with the explicit
   `production` profile and `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`. The CI
   deploy path now gates startup on schema readiness; it does **not** apply SQL.
   Verify backend health before restoring writes. Do not run a standalone
   Compose deployment with its local development defaults (`ddl-auto=update`)
   against the migrated database; that setting may try to narrow the column.

For an **explicitly disposable local** Compose database, the manual migration
command is:

```sh
docker compose exec -T db sh -c \
  'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < backend/sql/20260917_payment_money_invariants.sql
```

This example is not authorization to execute it on production. There is no
automatic production migration or credential export.

## What to verify

The migration adds nullable snapshot scale/provider, widens the nullable rate,
labels previously unlabeled submissions `v1`, and gives older writers a `v1`
default while rejecting explicit NULL versions. It backfills scale `2` only
for legacy rows with a persisted rate and provider only from their persisted
source. It does not invent missing rates or restore lost historical precision.
On rerun, existing version `2` NULL metadata remains NULL and detectable.

The financial audit returns one row per finding across seven categories:
approved outcome vs trip allocations, approved outcome vs reported allocations,
pending legacy cross-currency conversion vs the *persisted* rate/amount,
incomplete version-2 cross-currency snapshots, missing calculation versions,
invalid rate scales, and invented same-currency rates. A nonempty result is a
diagnostic, **not** approval to recalculate or patch financial history. Legacy
receipts can also contribute to installment balances; do not compare each
installment's paid amount to allocations alone.

## Rollback boundary

If an application deployment must be rolled back, stop cross-currency payment
writes until a compatible backend returns. Keep the widened column, added
columns, default, and constraint in place. Never shrink the rate, drop metadata,
or reprice persisted submissions/outcomes as an application rollback.
