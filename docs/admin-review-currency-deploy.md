# Coordinate outcome snapshot migration and backend deployment

Deploy the schema and the complete outcome writers together. T1 supplies SQL,
readiness, and migration tests only; entity fields and every review/reject/manual
writer belong to T2. T1 alone is **not deployable**: the old backend omits outcome
currency, so new inserts fail after `currency NOT NULL`. Do not add a default,
trigger, or readiness bypass to hide that incompatibility.

## Operator sequence

The approved same-PR exception is `MISMO PR, SIZE:EXCEPTION`. After all feature
tasks and gates are complete, use this exact order:

1. **Merge** the coordinated feature PR.
2. **VPS fetch/reset** to the authorized merged revision.
3. **Manual SQL**: quiesce old outcome writers, back up/audit historical data,
   confirm existing submission/money/attachment/manual prerequisites, then apply
   `backend/sql/20261005_admin_review_currency.sql` transactionally.
4. **Readiness**: run `scripts/check-payment-schema-readiness.sh`. All existing
   checks and `admin_review_currency_schema_readiness.sql` must return `READY`.
5. **Deploy backend with ddl validate**, never production `update`.
6. **Smoke** original submissions, independent administrative snapshots, manual
   outcomes, history/export currency labels, balances, and exact void reversal.

The pipeline can correctly abort with `NOT_READY` after fetch/reset but before
manual SQL. Apply the manual migration, verify readiness, then rerun deployment
at the same authorized revision. Do not bypass readiness or weaken constraints.
This document grants no remote-session, SQL execution, or deployment authority.

## Commands for an explicitly authorized operator

These commands are derived from `.github/workflows/ci-cd.yml` and
`docker-compose.yml`: checkout `/opt/apps/Gestion_pagos_viajes`, database service
`db`, backend service `backend`. Run them **on the authorized VPS session**, not
on a developer checkout. Replace `<authorized-merged-sha>` with the reviewed
merged revision. No host, SSH credential, backup destination, or smoke account is
specified here; the operator must authorize/provide them separately. Never copy
local test credentials into production.

1. After merge, update the checkout with the pipeline's revision guard:

   ```bash
   cd /opt/apps/Gestion_pagos_viajes
   DEPLOY_SHA='<authorized-merged-sha>'
   git fetch origin
   git cat-file -e "$DEPLOY_SHA^{commit}"
   test "$(git rev-parse origin/main^{commit})" = "$DEPLOY_SHA"
   git reset --hard "$DEPLOY_SHA"
   test "$(git rev-parse HEAD)" = "$DEPLOY_SHA"
   ```

   `reset --hard` discards tracked checkout changes; inspect and preserve any
   operator-owned changes before this step. Coordinate the automatic deployment
   job: it may correctly fail preflight until the manual SQL below is applied.

2. Pause incoming payment/manual/review jobs and stop **all old outcome writers**
   before the NOT NULL migration. For this Compose backend:

   ```bash
   docker compose stop backend
   ```

   Take and verify a restorable database backup using the site's approved backup
   procedure; audit historical rows and prerequisites before proceeding. No
   backup path is invented here. Database service `db` must remain running.
   This rollout requires coordinated write downtime: keeping an old backend
   alive after SQL is not a zero-downtime deployment strategy.

3. Apply the additive transaction, then run all four read-only checks:

   ```bash
   docker compose exec -T db sh -c \
     'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
     < backend/sql/20261005_admin_review_currency.sql
   ./scripts/check-payment-schema-readiness.sh
   ```

   Required checks: money, submission attachments, manual imputation, and admin
   review currency. Require `Payment schema preflight passed.`; any SQL failure
   or `NOT_READY` blocks deployment. Existing prerequisite migrations are not
   blindly replayed by this runbook; reconcile their readiness first.

4. Only after readiness, deploy the coordinated backend, matching the pipeline:

   ```bash
   SPRING_PROFILES_ACTIVE=production SPRING_JPA_HIBERNATE_DDL_AUTO=validate \
     docker compose up -d --build backend
   docker compose logs --tail=100 backend
   ```

   Confirm successful startup with Hibernate schema validation and no writer
   failures; keep general writes paused until the smoke below passes. If the
   pipeline aborted at preflight, rerun it at this same authorized SHA; it still
   rejects a stale main revision. This
   workflow deploys **backend only**: coordinate the updated frontend via the
   site's separately authorized frontend release process (none is defined in
   this workflow).

5. Smoke using authorized accounts and controlled financial records: original
   ARS → administrative USD on a USD trip, then original USD → administrative
   ARS on a USD trip with a historical quote for the reported payment date.
   Check immutable originals, separate admin amount/currency/FX, sequential trip
   credits, original-currency remainders, history/export labels, manual creation,
   and exact void reversal without new FX. Do not fabricate a production smoke
   account or silently mutate customer history for this check. Resume general
   writes only after successful smoke and operator confirmation.

## Historical audit and rollback

The backfill covers every old outcome status/source and copies only the original
persisted submission currency and FX evidence. Outcome money, status, reviewer,
allocations, and submission history do not change. Missing metadata remains NULL;
no current quote or fabricated provider is used. Reruns never overwrite outcomes
whose independent currency is already populated. Inspect wrong types/constraints
or invalid legacy currency/scale before rollout; failed SQL must roll back.

After migration, stop incompatible writers until the coordinated backend starts.
After any new snapshot writes, retain additive columns and audit evidence. Rollback
needs a reader/void path that honors persisted outcome currencies; restoring old
writers or dropping columns is not a safe financial rollback. Disposable full-stack
and concurrency harnesses now apply this migration and require T2 writers before
their review scenarios can pass. The two focused Chromium scenarios are distinct
from the still-required full harness/concurrency/aggregate gates.
