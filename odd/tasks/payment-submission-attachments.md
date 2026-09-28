# Multiple receipts per payment submission

## Objective and scope
Allow 0–5 ordered JPG, PNG, WEBP or PDF files (up to 5 MB each) to support **one** PaymentSubmission. Keep all financial fields, preview token, allocation, approval, void and concurrency behavior unchanged. Preserve legacy `fileKey` for existing clients and historical rows. The user subsequently authorized commits, push, and one PR targeting `main`. Do not merge or deploy. Do not alter the pre-existing untracked `PR-47-AUDIT.md` or `openspec/`.

## Evidence and constraints
- Existing storage validates each file; filesystem writes precede database commit and need compensation when a request fails.
- Production uses explicit SQL migrations and schema validation. Roll out the additive migration before deploying the backend; frontend follows the backend.
- Existing JSON registration supports zero attachments; the current browser UI requires one. The new contract is 0–5.
- TDD mode: no ODD-specific setting found; no strict-TDD assumption from SDD-only cache. Run focused and full functional checks with existing Maven/Vitest runners.
- Route: delegated direct. More than four files require mapping; backend/frontend changes span multiple non-trivial files. One writer owns implementation.
- Advisory task size: roughly 400 authored changed lines per coherent unit, not a cap. The maintainer explicitly approved `size:exception` for one PR targeting `main`; do not shrink tests or documentation to meet the review budget.

## Tasks
- [ ] ATT-01 — Add ordered attachment entity and safe additive SQL/backfill/readiness; update service, storage compensation, multipart, legacy read fallback and modern DTOs. Acceptance: 0/1/2/5 work, 6 rejects without writes, invalid/oversize and mid-batch failure leave no persisted submission or new files; modern and historical reads return ordered keys. Checks: focused backend tests, SQL/readiness checks.
- [x] ATT-02 — Extend typed frontend upload and all receipt display surfaces (user, admin, drawer, history), preserving legacy fallback. Acceptance: simple file list/count, 5-limit client validation, multipart repeated `files`, image previews and individual PDF links, no Zod casts. Checks: focused frontend tests.
- [ ] ATT-03 — Run applicable full backend/frontend tests, build, lint and schema readiness; inspect exact diff, report migration and rollout order plus failed/skipped checks. Prepare the authorized PR without merging or deploying. Acceptance: evidence of command outcomes and accurate local changed-file list.

## Progress and next step
Implementation is present on `feat/payment-submission-attachments`, based on `main`. Issue #52 is approved and the user authorized a single PR with `size:exception`; PR preparation does not authorize merging or deployment. ATT-01 remains open for PostgreSQL-backed migration/readiness and integration verification. ATT-02 is complete: 47 focused tests and the full frontend suite (127 tests across 28 files) passed, as did build and lint (3 warnings, 0 errors). ATT-03 remains open: after removing ignored, stale Maven `target/` class files, 11 focused backend tests and the package build passed, but the full backend suite had 200 Testcontainers/context errors among 362 tests because Docker was unavailable. The SQL readiness checks have only structural tests; no database was contacted. A read-only safety check found and prompted fixes for partial attachment cleanup and migration reruns. Rollback compensation retries and logs failed deletion, but a persistently failing filesystem deletion has no durable retry queue; manual reconciliation remains a rollout risk. Native review has started but no reviewer result or approval exists; updating this document after the review snapshot means any later review must use a fresh candidate. Next: prepare the authorized PR with these limitations stated, then run PostgreSQL-backed checks and readiness against an authorized local database. Do not merge or deploy.

## Work-unit evidence
- Backend: `14f021f3947dda4dc650ccc6e6b7e9a25cad21ec` (`feat(payment): persist ordered submission attachments`). Focused unit/SQL-structure tests: 11 passed; package build passed. PostgreSQL runtime harness: unavailable without Docker. Rollback boundary: revert the backend/service/schema change as one unit; retain the legacy column and physical files.
- Frontend: `e3562891ec38e3eac4840b492cb90486a554baee` (`feat(payment): upload and display multiple receipts`). Focused tests: 47 passed; full suite: 127 passed; build and lint passed with 3 warnings. Browser runtime harness: not exercised locally. Rollback boundary: revert the frontend upload/display unit while retaining backend's backwards-compatible single-file field.
- Delivery: a draft PR will target `main`; CI and native review are still pending. No merge or deployment is authorized.
