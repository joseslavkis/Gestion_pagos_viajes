# PR #47 Payment Money Remediation

## Objective

Close the verified payment-money correctness and rollout gaps on PR #47 while preserving the existing payment API intent, audited financial history, and deployment safety. The defects allow a rounded reverse-FX suggestion to exceed the remaining balance, invalid approval input to fall back to the full reported amount, selected-installment input to drift to enrollment-wide debt, and a schema migration to be bypassed by automatic backend deployment.

## Baseline and authorization

| Item | Baseline |
|---|---|
| Worktree / branch | `/Users/joseslavkis/Documents/Gestion_pagos_viajes` / `fix/payment-money-invariants` |
| Review candidate | PR #47; initial authorized head `efbbe1c746f2e3162600f0cf11e7ec9a1c08fa17`; previously pushed PR head immediately before the business-date correction was `5c11d7eb5b485d2c0a86d1d62f851e074a0e0407` |
| Base | `main@cb3d8f4ec8d51e460c548154e35bd51efb4ceb7b` |
| Tracking issue | #46, approved and linked to PR #47 |
| Delivery boundary | Keep the existing PR branch and single PR. The user explicitly authorized pushing exactly `fix/payment-money-invariants` through the configured Git transport after local checks; after that, remote reads are limited to PR #47 check runs. No merge, deploy, VPS/production access, or production SQL is authorized. |

**Implementation status:** PMR-01 through PMR-04, the coverage-only supplement, and the test-only business-date correction are committed on the existing branch. The correction has passed the local checks recorded below; its push and post-push PR checks remain separate from local verification. No merge, deployment, production access, or production SQL execution is authorized or has occurred.

## Task-document persistence

The local task file is canonical. Prior Engram mirror writes and the latest resume attempt were rejected because multiple active project sessions made the target ambiguous; no session ID will be invented. The mirror remains pending and should be resynchronized when Engram has a unique active session.

## Problem and why

Payment amounts are audit-sensitive. Existing review findings show that a reverse conversion can round a suggested payment upward past the balance (for example, 200.00 ARS at 3 ARS/USD must not suggest 66.67 USD, because it converts back to 200.01 ARS); invalid edited approval text can be interpreted as approval of the full reported amount; and a remaining-amount request can lose the selected-installment context. Separately, a required manual PostgreSQL migration is not currently guaranteed to precede automatic backend deployment. These gaps undermine FIN-001 conservation and FIN-002 exchange-rate snapshot fidelity, and require regression coverage at the payment, UI, persistence, and release boundaries.

## Authorized implementation scope

The later implementation is limited to these four work units:

1. Backend payment rules: safe reverse-FX limits, provider-rate contract validation, and exact monetary rejection/conservation behavior.
2. Frontend approval and input state: strict approval parsing, selected-installment context, and preservation of current/manual input.
3. Migration/deploy safeguards and documentation: additive schema rollout safety, a deploy precondition, and clear operator/user-facing documentation.
4. Full-stack lifecycle/invariant coverage: deterministic regression tests across preview, registration, persistence/reload, review, and reversal.

Do not broaden this scope into new payment products or unrelated cleanup. Keep API changes, tests, migration/deploy behavior, and documentation limited to the above findings and their necessary regression coverage.

## Invariants and constraints

- **FIN-001:** approved outcomes, persisted allocations, and installment `paidAmount` changes must reconcile exactly in both reported and trip currency. Reject non-imputable amounts before financial mutation; never silently clamp, discard, or hide a residual.
- **FIN-002:** use one explicit exchange-rate snapshot through preview, registration, persistence, and review. Do not refetch or reconstruct the rate when reviewing or reversing a payment.
- Preserve each existing per-flow lock order. In particular, registration must retain **Trip → Installments**; do not introduce lock reordering in review, void, or unrelated trip flows.
- Do not add over-approval, overpayment, credit, or “saldo a favor” capabilities. A rounded amount that exceeds the balance must be rejected or capped to a safe payable suggestion, not credited.
- Do not rewrite, revalue, or recalculate approved historical financial records. Any schema change must be additive and preserve existing values.
- No production access, live-database access, production SQL execution, secrets, or real FX dependencies. Migration verification may use only disposable test infrastructure in the later implementation; this document-authoring phase executes no SQL.
- Preserve root `AGENTS.md` and `openspec/` byte-for-byte. Local commit `7519c991084463462201d17de5b6a52b955e8fe8` (`add agents.md`) was present when implementation resumed; this writer did not create or modify it. `openspec/` remains untracked. Do not create or update SDD artifacts.
- During initial task-document refinement, the only permitted edit was this file; implementation is now authorized only within PMR-01 through PMR-04.
- Preserve the repository's exact-SHA deployment, stale-deploy protection, serialization, CI prerequisites, and lock/concurrency safeguards. Do not deploy or weaken any existing gate.

## Resolved TDD mode and applicable checks

**`strict_tdd: true`** — resolved from Engram project testing-capabilities observation **#621**. That observation records the two-stack test runners and resolves strict TDD to true. For every behavior change, follow **RED → GREEN → REFACTOR**: first add a deterministic failing regression and record its exact result; implement the smallest fix; get the focused and required suite green; then refactor without changing the contract and rerun the relevant checks. Do not edit production behavior without a demonstrated RED for the target behavior.

Required full-suite runners:

```bash
cd backend && ./mvnw test
cd frontend && npm ci
cd frontend && NODE_OPTIONS=--no-experimental-webstorage npm test
cd frontend && npm run build
cd frontend && npm run lint
./scripts/test-payment-concurrency.sh
./scripts/test-payment-full-stack.sh
./scripts/test-payment-money-invariants.sh
```

Use `npm ci`, never `npm install`, for CI-style frontend verification. The aggregate payment script runs the backend, frontend, concurrency, and full-stack checks; record exact commands and results rather than inferring a pass from another layer. Final diff review after implementation must include `git status --short`, `git diff --check`, and `git diff`.

Applicable additional checks during implementation:

- `cd frontend && npm run build`
- `cd frontend && npm run lint`
- Run the existing payment concurrency harness if payment locking/concurrency paths change; keep it isolated and never point it at production.
- Verify migration behavior only against disposable PostgreSQL/Testcontainers infrastructure. Do not connect to a live database or execute SQL in this document-authoring phase.
- Validate Compose with `docker compose config` only if Compose configuration changes.
- Record exact commands and results; do not claim a check passed unless it was run. The initial task-document authoring phase ran no tests; implementation evidence is recorded per work unit.

## Work units

All work-unit statuses begin **pending**. Each unit follows strict TDD, includes its regression tests with the behavior, and closes with one focused Conventional Commit on the existing feature branch. Update this ODD task record task-by-task as each unit progresses. No unit may be marked complete without acceptance, verification, and commit evidence.

### Delegation and verification protocol

- **Route:** each PMR unit is `delegated direct` to one bounded writer because it touches 2+ non-trivial files. Broad inspection/mapping is already delegated to `explore`; preparation reads belong to the writer.
- **Parent checks:** after the writer completes, the parent performs the risk assessment and one spot check, then records both in that unit's verification evidence.
- **Independent verification:** RDD status is currently `off`, so do not launch receipt-based reviews or native lenses. There is no routine per-unit reviewer or semantic review. A separate independent verifier is required only when the actual assessed tier is high or unassessable, or when it is medium and the writer profile is small-model. Otherwise record `N/A` with the assessed tier and writer profile.

### PMR-01 — Enforce backend payment amount rules

**Status:** implemented and committed; the coverage-only supplement closed the evidence gap; final parent spot check and fresh independent test-coverage verification passed.

**Intent:** make payment suggestions and submissions safe at monetary rounding boundaries and reject invalid conversions before persistence.

**Acceptance criteria:**

- A safe reverse-FX maximum is verified in both currency directions with exact and non-exact conversions, half-cent boundaries, explicit residuals, and a too-small `UNPAYABLE` result. With 200.00 ARS remaining at 3 ARS/USD, 66.67 USD is rejected because it converts to 200.01 ARS; the maximum is 66.66 USD, which converts to 199.98 ARS and leaves an explicit 0.02 ARS residual.
- One strict `maxAllowedPaymentAmount` is authoritative from calculation through preview/token validation and registration; do not recompute or round a second maximum in a later layer. A candidate above the safe maximum is rejected before mutation.
- Provider-rate identity cases cover `1E+3`, integer rates, and rates with 2, 3, and 8 decimal places; a rate with more than 8 meaningful decimal places is rejected. Normalize negative scale exactly (`1E+3` to integer `1000`) before calculation, token creation/validation, persistence, and reload. Accepted rate value and scale identity must remain stable across those boundaries.
- Preserve `1234.567` exactly through preview, registration, persistence/reload, review, and void. Review and reversal reuse that stored snapshot and never refetch or reconstruct a provider rate.
- FIN-001 conversion and allocation checks reject over-balance amounts with no submission/outcome/allocation/`paidAmount` mutation and no hidden residual or credit ledger.
- Accepted inputs conserve the required reported- and trip-currency amounts exactly; legitimate cents remain payable and every non-imputable residual remains explicit.
- The existing per-flow lock order remains unchanged.

**Implementation route:** write planner/service/API regression cases for the safe reverse-FX boundary and provider-rate contract; capture RED; correct the backend policy and affected call path with `BigDecimal`-based decisions; capture GREEN; simplify/refactor only after invariants pass. Keep API error semantics consistent with existing validation behavior.

**Verification:** add focused tests for both safe-limit directions, exact/non-exact and half-cent cases, `UNPAYABLE`, canonical rate identity/scale, persisted `1234.567`, and no-mutation rejection. Run `cd backend && ./mvnw -Dtest=PaymentMoneyPolicyTest,PaymentRestControllerFreeAmountTest test`, then the full backend suite and aggregate script as applicable.

**Route evidence:** `delegated direct` to one bounded writer. Trigger: this unit changes 2+ non-trivial backend policy/service/API and regression-test files; broad inspection/mapping is delegated to `explore`, and preparation reads belong to the writer.

**Verification evidence:** the final parent risk assessment (`gentle-ai review assess --cwd ... --json`) failed closed because untracked `openspec/` required explicit inventory; treat the final tier as high/unassessable. RDD is off, so no native review lifecycle/status was run, and no user-owned untracked files were inspected. Parent money spot check `./mvnw -Dtest=PaymentMoneyPolicyTest,PaymentRestControllerFreeAmountTest test` passed (40 tests). Fresh independent test-coverage verification passed after the supplement: exact over-conversion rejection leaves persisted financial state unchanged; a later anchor is rejected while installment #1 remains valid; half-cent residuals are asserted in both currency directions; and fixed-Clock endpoint tests reject future dates for same- and cross-currency payments.

**Progress:** implementation committed as `efcfa467fbe5d340ec7f2bac27a62d40a6313bc6`. RED `./mvnw -Dtest=PaymentMoneyPolicyTest test`: 9 tests, 1 failure as expected (`1E+3` retained scale -3 instead of canonical scale 0). Integration RED `./mvnw -Dtest=PaymentMoneyPolicyTest,PaymentRestControllerFreeAmountTest test`: 33 tests, 4 expected failures for same/cross-currency future dates, sub-cent approval, and anchor-vs-total REMAINING. GREEN/refactor rerun `./mvnw -Dtest=PaymentMoneyPolicyTest,PaymentAllocationPlannerTest,PaymentPreviewTokenServiceTest,PaymentBusinessDatePolicyTest,PaymentRestControllerFreeAmountTest,PaymentRestControllerTest,PaymentSubmissionSnapshotContractTest test`: 75 tests, 0 failures/errors/skips. CASE G keeps the 10.16 ARS suggested amount separate from its 15.23 ARS safe maximum. At that implementation checkpoint, parent assessment and spot check were still pending; see final verification below.

**Conventional Commit placeholder:** `fix(payment): enforce safe monetary limits and rate identity`

### PMR-02 — Preserve frontend approval and payment input state

**Status:** corrected and committed in `855580f`; focused and full verification passed; final parent spot check and fresh independent token/coverage verification passed.

**Intent:** make approval and amount-entry state explicit so malformed edits cannot approve more than intended and selected-installment context is not replaced by broader debt.

**Acceptance criteria:**

- The review parser strictly rejects `250.`, `abc`, `-1`, and `1.005` without issuing a request or mutating state; it preserves the entered text and disables Save. `0` remains valid for total rejection. Approval above the reported amount is rejected.
- `REMAINING` means the current balance of the validated first pending anchor installment, never the enrollment-wide sum. For `MANUAL`, keep `totalPendingAmountInTripCurrency` separate from the maximum payment amount. A manual amount of 500 across installments of 240 + 240 + 240 allocates 240 + 240 + 20.
- The backend derives monetary authority, pending totals, safe maxima, and allocations from validated persisted context. The frontend must not provide authoritative totals, FX conversions, maximums, or allocations.
- A failed or stale calculation does not overwrite newer user input; manual edits remain visible and are not silently relabeled or replaced.
- Frontend tests cover valid partial approval, total rejection at zero, over-reported approval, invalid/incomplete edits, selected-installment `REMAINING`, the `MANUAL` waterfall vector, and stale/manual-input preservation without introducing JavaScript floating-point authority for business decisions.
- Newly issued preview tokens use exactly `cv=2` and retain `intent`; tokens without `cv=2` or with any other version are invalid immediately. Do not add a grace period or infer a version for ambiguous tokens.

**Implementation route:** add component/hook tests for the invalid fallback and installment-context regressions; capture RED; tighten parsing and state/request context handling at the existing payment UI boundary; capture GREEN; refactor while preserving accessible validation feedback and the entered value.

**Verification:** run `cd frontend && NODE_OPTIONS=--no-experimental-webstorage npm test`, with focused assertions that the four invalid strings cause no request, text remains unchanged, Save is disabled, zero rejection remains actionable, and anchor/manual calculations keep their distinct meanings. Then run frontend build and lint.

**Route evidence:** `delegated direct` to one bounded writer. Trigger: this unit changes 2+ non-trivial frontend component/hook and regression-test files; broad inspection/mapping is delegated to `explore`, and preparation reads belong to the writer.

**Verification evidence:** the final parent risk assessment (`gentle-ai review assess --cwd ... --json`) failed closed because untracked `openspec/` required explicit inventory; treat the final tier as high/unassessable. RDD is off, so no native review lifecycle/status was run, and no user-owned untracked files were inspected. Parent focused check `./mvnw -Dtest=PaymentPreviewTokenServiceTest,PaymentMoneyInvariantBoundaryTest,PaymentSubmissionSnapshotContractTest test` passed (24 tests). Fresh independent verification passed: the issuer emits exactly `cv=2`, a token without `cv` is rejected, and the PMR-01/02/04 supplemental test-coverage findings listed below are covered. This is bounded token/coverage verification, not a full PR review.

**Progress:** at this earlier checkpoint implementation was verified locally and the PMR-02 work-unit commit was pending; see the commit evidence below. RED `NODE_OPTIONS=--no-experimental-webstorage npx vitest run src/features/payments/pages/__tests__/PendingReviewPage.test.tsx`: 5 tests, 2 failures because Save remained enabled for invalid and over-reported values; GREEN rerun: 5 passed. RED `NODE_OPTIONS=--no-experimental-webstorage npx vitest run src/features/users/pages/__tests__/UserDashboardPage.test.tsx -t "seeds REMAINING input"`: 1 failure (input showed 200 instead of server balance 199.99); GREEN rerun after canonical remaining wiring: 1 passed. Backend endpoint RED `./mvnw -Dtest=PaymentRestControllerFreeAmountTest#myInstallments_returnsCanonicalServerComputedRemainingBalance test`: 1 missing-field failure; after local Docker Desktop was started, GREEN rerun: 1 passed. DTO naming RED `./mvnw -Dtest=PaymentCalculationResponseDTOTest test`: 1 expected failure because JSON exposed `remainingAmount`; GREEN `./mvnw -Dtest=PaymentCalculationResponseDTOTest,PaymentUserInstallmentDTOTest test`: 2 passed. Manual input RED `NODE_OPTIONS=--no-experimental-webstorage npx vitest run src/features/users/pages/__tests__/UserDashboardPage.test.tsx -t "does not create an enabled calculation query for a sub-cent manual amount"`: 1 failure because the query cache contained an enabled `MANUAL` query for `1.005`; GREEN rerun after cent-strict payload normalization: 1 passed. Latest focused frontend run over `PendingReviewPage.test.tsx`, `UserDashboardPage.test.tsx`, `payments-service.test.tsx`, and `payments-dtos.test.ts`: 4 files, 31 tests passed. Latest focused backend run `./mvnw -Dtest=PaymentRestControllerFreeAmountTest,PaymentCalculationResponseDTOTest,PaymentUserInstallmentDTOTest test`: 29 tests passed. An earlier API test attempt failed to load the Spring context while Docker was stopped; local Docker was started, `docker info` then reported Server 29.7.2 / Linux arm64, and the focused Testcontainers run passed. Full suites/build/lint and payment harnesses remain for final verification. At that checkpoint, parent checks were pending; final results are recorded above.

**Verification update:** `cd backend && ./mvnw test` passed: 333 tests, 0 failures/errors/skips. `cd frontend && npm ci` passed (402 packages installed; npm reported 25 dependency advisories and install scripts awaiting approval). `cd frontend && NODE_OPTIONS=--no-experimental-webstorage npm test` passed: 27 files, 112 tests. `cd frontend && npm run build` passed. `cd frontend && npm run lint` passed with 0 errors and 3 warnings in unmodified TokenContext/session files. The isolated concurrency, full-stack, and aggregate payment harnesses also passed; details are recorded under PMR-03.

**Commit evidence:** local commit `d22dbd2b13891ea2b0ab6af6911977c80a0d0f5d`, parent `7519c991084463462201d17de5b6a52b955e8fe8`; `fix(frontend): preserve strict payment review and input state`. Change size: 412 additions, 79 deletions. At that original commit checkpoint, parent assessment and spot check were pending; see final verification below.

**Independent-verifier correction:** verifier found the new token writer had drifted to `cv=3`, contrary to the required `cv=2` contract. RED `./mvnw -Dtest=PaymentPreviewTokenServiceTest#issuedTokenDeclaresCalculationVersionTwoAndIntent test` — 1 expected failure because the writer emitted `cv=3`. GREEN `./mvnw -Dtest=PaymentPreviewTokenServiceTest test` — 10 passed; the base-backend token fixture includes `intent` and proves missing `cv` requires immediate recalculation, while exact `cv=2` remains valid. Controller persistence/response suite `./mvnw -Dtest=PaymentPreviewTokenServiceTest,PaymentRestControllerFreeAmountTest,PaymentRestControllerTest test` — 52 passed. Corrective commit `855580f8afb6bbb359cde224e6331315fcdd6032` (parent `276073ba542514ba79acaaa3cf05c61840f009ee`) preserves the original PMR-02 commit unchanged.

### PMR-03 — Guard migration rollout and document operations

**Status:** corrected and committed in `a3a369a`; full verification, parent spot checks, and fresh independent verification passed.

**Intent:** prevent a backend requiring the payment schema change from being deployed before the migration is confirmed, while keeping historical data and the existing deployment safety model intact.

**Acceptance criteria:**

- The additive migration is transactional and idempotent. Disposable PostgreSQL tests start from the old schema with historical rows, verify preserved values after migration, prove a second run is a no-op, start the migrated schema with Hibernate `ddl-auto=validate`, and persist/reload an 8-scale rate.
- An older writer that omits `calculation_version` cannot create a NULL version: backfill existing NULLs to `v1` in the explicit migration, then enforce `DEFAULT 'v1'` and `NOT NULL` so legacy inserts that omit the new column receive `v1` and explicit NULL is rejected. Test both insert shapes. This remains an operator-run schema migration; do not add startup migration, automatic production backfill, or Hibernate `ddl-auto=update` behavior.
- Legacy `v1` pending submissions remain reviewable from their stored snapshot without provider refetch or reconstruction. All preview tokens issued under the old calculation version become invalid immediately; the user must recalculate before registration.
- Production profile configuration uses `ddl-auto=validate`. Deployment has an explicit compatibility precondition for the exact release and fails before backend container/application mutation when the schema is incompatible. It does not silently execute production SQL. Exact-SHA deployment, stale-deploy protection, deployment serialization, concurrency safeguards, and existing CI prerequisites remain intact.
- The preflight consumes the shared read-only SQL query. It requires exact `DEFAULT 'v1'`, `NOT NULL`, and the validated expected scale-range constraint definition; `calculation_version=2` cross-currency rows must carry rate, scale, provider/source, and quote requested/effective dates.
- Keep Vercel production paused until the compatible backend is deployed and healthy; promote the intended frontend release only afterward.
- Operator rollout documentation states the safe order: verify backup; apply the additive SQL migration manually; verify schema/version and migration results; deploy and verify the exact compatible backend SHA; then promote/deploy the frontend; finally run same- and cross-currency smoke flows. Rollback reverts application artifacts only, never shrinks snapshot precision or rewrites history; suspend cross-currency payments until compatibility is restored.
- Include a Compose-local SQL command for an explicitly local/disposable database, for example:
  ```sh
  docker compose exec -T db sh -c \
    'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    < backend/sql/20260917_payment_money_invariants.sql
  ```
  This is not a production command or an authorization to run SQL. Production SQL remains a separate, manually approved operator action.
- Docs identify the required checks and non-goals, including no live FX and no production SQL execution by this work unit.

**Implementation route:** inspect the existing migration and deploy workflow against the precondition; add a failing test/static workflow assertion for missing migration readiness; capture RED; make only additive migration/deploy-note/workflow changes needed to enforce ordering; prove schema behavior against disposable PostgreSQL and capture GREEN; refactor documentation and workflow checks without changing unrelated deployment semantics.

**Verification:** run `cd backend && ./mvnw -Dtest=PaymentMoneyInvariantBoundaryTest test`, the full backend suite, and `./scripts/test-payment-money-invariants.sh`. Verify migration twice against disposable PostgreSQL and verify Hibernate validation and the deployment precondition before any backend mutation; inspect the production-profile setting, exact-SHA/stale/concurrency gates, and Vercel promotion order. Do not execute production SQL.

**Route evidence:** `delegated direct` to one bounded writer. Trigger: this unit changes 2+ non-trivial migration/deploy workflow, documentation, and regression-test files; broad inspection/mapping is delegated to `explore`, and preparation reads belong to the writer.

**Verification evidence:** the final parent risk assessment (`gentle-ai review assess --cwd ... --json`) failed closed because untracked `openspec/` required explicit inventory; treat the final tier as high/unassessable. RDD is off, so no native review lifecycle/status was run, and no user-owned untracked files were inspected. Parent readiness spot checks passed: `./mvnw -Dtest=PaymentMoneyInvariantBoundaryTest,PaymentSubmissionSnapshotContractTest test` (12 tests), followed after the token/preflight correction by `./mvnw -Dtest=PaymentPreviewTokenServiceTest,PaymentMoneyInvariantBoundaryTest,PaymentSubmissionSnapshotContractTest test` (24 tests). Fresh independent verification passed: exact `v1` default and validated constraint/shared readiness SQL reject `v10`, a wrong same-name constraint, `NOT VALID`, and missing v2 cross-currency metadata; the backend preflight precedes container start; migration, rollback, Vercel ordering, and the legacy v1 contract were verified. No production preflight or SQL was executed.

**Progress:** implementation, commit, and aggregate verification complete. RED: `PaymentSubmissionSnapshotContractTest#persistenceDefaultsMissingCalculationVersionToLegacyV1` failed as expected (`v1` expected, `null` observed). GREEN/refactor: `./mvnw -Dtest=PaymentMoneyInvariantBoundaryTest,PaymentSubmissionSnapshotContractTest test` — 11 tests, 0 failures/errors/skips, including disposable PostgreSQL migration idempotence, historical preservation, omitted-column default, explicit-NULL rejection, and Hibernate validation. Process-level readiness regression: `./mvnw -Dtest=PaymentMoneyInvariantBoundaryTest#schemaPreflight_failsClosedForIncompatibleSchema test` — 1 passed for both `NOT_READY` and `READY` mock-Docker results. Full backend: `./mvnw clean test` — 329 tests, 0 failures/errors/skips. Aggregate `./scripts/test-payment-money-invariants.sh` passed: backend 329 tests; frontend 27 files/112 tests, build passed, lint 0 errors/3 pre-existing warnings; isolated same-currency review/void each produced exactly one success and one conflict and reversed the 100.00 credit; 2 cross-currency concurrency tests passed; all 3 Playwright CASE F/G tests passed. Compose validation: `docker compose --env-file .env.example config --no-env-resolution --quiet` passed; `bash -n scripts/check-payment-schema-readiness.sh` passed and the script is executable. A first unclean `./mvnw test` could not start tests because stale duplicate `TestcontainersConfiguration 2/3.class` files existed under `target`; `clean test` removed the stale classes and passed. The production preflight itself was not run against any database. Commit `40898896fd811e7b824e239c5d004261befd3af4`, parent `d22dbd2b13891ea2b0ab6af6911977c80a0d0f5d`: `fix(deploy): gate payment backend on schema readiness`. At that implementation checkpoint, parent assessment and spot check were pending; see final verification below.

**Conventional Commit placeholder:** `fix(deploy): gate payment backend on schema readiness`

**Independent-verifier correction:** verifier found the preflight accepted `v10` via `column_default LIKE '%v1%'` and trusted a same-named scale constraint without checking its definition or validation. RED `./mvnw -Dtest=PaymentMoneyInvariantBoundaryTest#schemaPreflightDoesNotAcceptBroadVersionDefaultMatches test` — 1 expected failure on that broad match; readiness-query regression also failed because the shared query did not exist. GREEN `./mvnw -Dtest=PaymentMoneyInvariantBoundaryTest#sharedReadinessQueryRejectsIncorrectDefaultConstraintAndVersionTwoFxMetadata test` — 1 passed against disposable PostgreSQL for migrated READY, wrong `v10` default, wrong same-name constraint definition, NOT VALID constraint, and missing v2 cross-currency provider metadata. The exact requested focused suite `./mvnw -Dtest=PaymentPreviewTokenServiceTest,PaymentMoneyInvariantBoundaryTest,PaymentSubmissionSnapshotContractTest test` — 24 passed; `shellcheck scripts/check-payment-schema-readiness.sh` passed. The shared query is used by both the shell preflight and Testcontainers regression. Corrective commit `a3a369a6d6647a68cb0e17d5bf2bb9f2233533ac` (parent `855580f8afb6bbb359cde224e6331315fcdd6032`) preserves the original PMR-03 commit unchanged.

**Final verification:** `./mvnw -Dtest=PaymentMoneyInvariantBoundaryTest test` — 9 tests passed against disposable Testcontainers PostgreSQL. `cd backend && ./mvnw test` — 333 passed; `./scripts/test-payment-concurrency.sh` — isolated same-currency review/void each produced one success and one conflict, plus 2 cross-currency concurrency tests passed; `./scripts/test-payment-full-stack.sh` — 4 browser tests passed; `./scripts/test-payment-money-invariants.sh` — backend 333, frontend 27 files/112 tests, build/lint, isolated concurrency, and all 4 browser cases passed. Required ShellCheck, Compose config, and `git diff --check` passed. No production preflight, production database access, or production SQL was run.

### PMR-04 — Prove payment lifecycle and invariant coverage

**Status:** implementation, aggregate verification, work-unit commit, and parent spot check are complete; the coverage-only supplement is independently verified. The final whole-change risk assessment remains high/unassessable as recorded below.

**Intent:** demonstrate that the backend and UI behavior remains financially consistent across the complete payment lifecycle, persistence reloads, approval, and reversal.

**Acceptance criteria:**

- Deterministic end-to-end coverage exercises preview → registration → commit/reload → full or partial review → void against disposable PostgreSQL, a real Spring backend, and the frontend, using test-only deterministic FX. Bind services to loopback, clean up child processes and containers on every exit, and cover both same- and cross-currency flows.
- Use synthetic test data and test-only credentials; no production SQL, production data, production secrets, live database, or external FX provider may be used.
- FIN-001 assertions reconcile outcome amounts, persisted allocations, and `paidAmount` deltas in both currencies; over-balance rejection leaves financial state unchanged.
- FIN-002 assertions show the accepted rate snapshot used through registration and review without live provider refetch or precision loss; void reverses persisted allocations rather than recalculating at a new rate.
- Preserve the named deterministic regression vectors A–J:

  | Vector | Required assertion |
  |---|---|
  | A | Review parser rejects `250.`, `abc`, `-1`, and `1.005` without request/mutation, preserves text, and disables Save; zero is valid for total rejection and approval cannot exceed the report. |
  | B | `REMAINING` uses the validated first pending anchor balance; `MANUAL` keeps `totalPendingAmountInTripCurrency` separate from the maximum and allocates 500 across 240 + 240 + 240 as 240 + 240 + 20. |
  | C | With 200 ARS remaining at rate 3, the safe maximum is 66.66 USD, converts to 199.98 ARS, and leaves an explicit 0.02 ARS residual; 66.67 USD is rejected. |
  | D | Safe maxima cover both currency directions, exact/non-exact and half-cent boundaries, explicit residuals, and a too-small `UNPAYABLE` result; the same `maxAllowedPaymentAmount` is reused once. |
  | E | Rate identity covers `1E+3`, integer, 2/3/8 decimal rates, and rejection above 8 decimals; normalize negative scale exactly before calculation/token/persistence/reload. |
  | F | Preserve the existing delayed-response intent vector: `20000 ARS` remains the user's intent through ARS → USD → ARS switches. |
  | G | Preserve the existing `0.01 USD` at `1015.50` vector: backend-authoritative result is `10.16 ARS` and `99.29` remains payable. |
  | H | Preserve `1234.567` across preview, registration, reload, review, and void; historical approval/void reuse stored quote dates and values. |
  | I | Inject a business-zone clock; future payment dates are rejected for both same- and cross-currency input, while historical approvals retain their original snapshot and void reverses persisted allocations. |
  | J | Partial cross-currency outcomes conserve reported and trip-currency amounts across installments; every remainder has explicit amount/currency/meaning, is not hidden or credited, and void reverses the persisted allocation exactly. |

- Regression coverage also includes migration/deploy safeguards where they cross the full-stack boundary, legacy `v1` review with no provider refetch, and immediate invalidation of old preview tokens so the user must recalculate.
- Existing lock-order and payment-concurrency tests remain effective if affected paths change. Test infrastructure is isolated from production and external FX.

**Implementation route:** add the lifecycle/invariant regression first and record RED against a concrete known defect; extend only the existing deterministic test harnesses as needed; apply any remaining minimal fix within PMR-01–PMR-03 boundaries; record GREEN and refactor results. If a proposed end-to-end case is already green, identify a missing observable contract and demonstrate its RED before changing production code.

**Verification:** run `./scripts/test-payment-full-stack.sh` for the deterministic loopback lifecycle and `./scripts/test-payment-concurrency.sh` for concurrent review/void and cross-currency integrity. Then run `./scripts/test-payment-money-invariants.sh`, backend tests, frontend tests/build/lint, and final diff checks. Browser-dependent checks must report an exact blocker rather than claim success if the pinned Playwright/Chromium runtime is unavailable.

**Route evidence:** `delegated direct` to one bounded writer. Trigger: this unit changes 2+ non-trivial lifecycle/invariant test and full-stack harness files; broad inspection/mapping is delegated to `explore`, and preparation reads belong to the writer.

**Verification evidence:** the final parent risk assessment (`gentle-ai review assess --cwd ... --json`) failed closed because untracked `openspec/` required explicit inventory; treat the final tier as high/unassessable. RDD is off, so no native review lifecycle/status was run, and no user-owned untracked files were inspected. Parent spot check passed: `./mvnw -Dtest=PaymentRestControllerFreeAmountTest#reviewPayment_partialCrossCurrencyAcrossInstallmentsConservesBothCurrenciesAndVoidReversesPersistedAllocations test` (1 test); the earlier CASE J check verified deterministic rate `1234.56`, split persisted conversion, partial approval/rejected residual, and exact void reversal. Fresh independent test-coverage verification passed after the supplement, including exact over-conversion no-mutation, later-anchor rejection while #1 remains pending, bidirectional half-cent residuals, and fixed-Clock future-date endpoint coverage.

**Progress:** RED `./scripts/test-payment-full-stack.sh` — the 3 existing CASE F/G tests passed; CASE J failed as expected because the fixture lacked its two-installment trip. GREEN `./scripts/test-payment-full-stack.sh` — 4 Playwright tests passed, including browser registration, persisted/reloaded snapshot, admin partial cross-currency review, spreadsheet UI void, and paid-balance reversal. Focused backend `./mvnw -Dtest=PaymentRestControllerFreeAmountTest#reviewPayment_partialCrossCurrencyAcrossInstallmentsConservesBothCurrenciesAndVoidReversesPersistedAllocations test` — 1 passed against disposable Testcontainers PostgreSQL; approved/rejected outcomes conserve reported and trip-currency values across two installments, and void reverses the persisted allocation. Final `./scripts/test-payment-money-invariants.sh` passed: backend 330 tests; frontend 27 files/112 tests; frontend build passed; lint passed with 0 errors/3 pre-existing warnings; isolated same-currency review/void and cross-currency concurrency checks passed; all 4 Playwright CASE F/G/J tests passed. An extra standalone E2E `tsc` check was attempted but the repository has no `@types/node`; its existing E2E node imports/process globals are not part of `tsconfig.json`'s build references. Commit `ed39192e99b9ddff5524a70d1328b14944477a65`, parent `40898896fd811e7b824e239c5d004261befd3af4`: `test(payment): prove lifecycle monetary invariants`. At that earlier checkpoint, the unit-level assessment/spot check was medium risk and independent verification was not required; see the later independent coverage verification and final high/unassessable parent assessment above.

**Commit evidence:** `ed39192e99b9ddff5524a70d1328b14944477a65` / parent `40898896fd811e7b824e239c5d004261befd3af4`; `test(payment): prove lifecycle monetary invariants`. Change size: 311 additions, 12 deletions.

### Coverage-only supplement — PMR-01 / PMR-04

**Status:** Closed. A fresh independent verifier identified four remaining evidence gaps; implementation behavior was already present, so no production code changed.

| Verifier finding | Added evidence |
|---|---|
| The exact rounded over-balance vector lacked a PostgreSQL/controller no-mutation assertion. | Register `0.99 USD` against `100.00 ARS` at a token-pinned `101.30` rate. The controller rejects the rounded `100.29 ARS` conversion, reports `maxAllowedAmount=0.98` and `residualInTripCurrency=0.29`, and leaves submissions, outcomes, allocations, total due, and `paidAmount` unchanged. |
| Later-installment anchor rejection lacked an explicit fixture proving installment #1 remained pending. | With #1 and #2 both pending, calculation from #2 conflicts; calculation from #1 succeeds and allocates only to #1. Fixture assertions verify installment ordering, balances, and zero paid amounts. |
| Safe-limit tests asserted the next cent exceeded the balance without asserting the resulting residual. | Each boundary vector now asserts the exact residual, including both currency directions at the half-cent cases, while continuing to call the single `PaymentMoneyPolicy.maxAllowedPaymentAmount`. |
| Same- and cross-currency future-date endpoint cases relied on system time. | The controller integration context provides a fixed qualified `paymentBusinessClock`; tests assert the selected clock instant/business date and endpoint error date. Payment-date fixtures use the same fixed business day. |

**RED/GREEN:** Before the fixed test clock, `./mvnw -Dtest=PaymentMoneyPolicyTest,PaymentRestControllerFreeAmountTest test` ran 38 tests with 2 expected failures: both future-date requests returned HTTP 200 because the test's 2026-01-02 date was earlier than the host's 2026-09-23 system date. This exposed a test-clock mismatch, not a production defect. With the fixed clock and added coverage, the same focused command passed 40 tests, 0 failures/errors/skips. The real Spring/controller and Testcontainers PostgreSQL paths passed; no financial service or date policy was mocked.

**Final verification:** `cd backend && ./mvnw test` passed 335 tests. `cd frontend && NODE_OPTIONS=--no-experimental-webstorage npm test` passed 27 files / 112 tests; `npm run build` passed; `npm run lint` reported 0 errors and 3 existing warnings. `shellcheck scripts/test-payment-concurrency.sh scripts/test-payment-full-stack.sh scripts/test-payment-money-invariants.sh` and `docker compose --env-file .env.example config --no-env-resolution --quiet` passed. Standalone `./scripts/test-payment-full-stack.sh` passed all 4 Playwright cases. Standalone `./scripts/test-payment-concurrency.sh` passed same-currency review/void (one success and one conflict each, exact single credit/reversal) and 2 cross-currency Testcontainers tests; 2 transient `curl: (52) Empty reply from server` readiness probes occurred while the backend started and the script retried successfully. `./scripts/test-payment-money-invariants.sh` passed with backend 335 tests, frontend 27 files / 112 tests, build, lint, isolated concurrency, and all 4 Playwright cases; its startup loop emitted 3 transient empty replies before succeeding. `npm ci` was not repeated per the follow-up instruction; the earlier 402-package install remains recorded above. The task changes are committed as `test(payment): cover remaining money boundaries`, the single supplemental commit directly after `908ed72e3911c0263534a43e49678b27474f535a`; no earlier commit was amended.

**Production-behavior conclusion:** The new PostgreSQL/controller vector confirms the existing planner rejects the `100.29 ARS` conversion before persistence; the anchor check rejects #2 while #1 is pending; and future-date policy rejects both currency paths when given the fixed business clock. The test gaps did not demonstrate a production behavior defect.

## Delivery strategy and review forecast

- **Strategy:** `exception-ok` — the existing PR #47 body explicitly records that the cumulative change exceeds the 400-line review budget and that the previously accepted single-PR `size:exception` applies. Do not use this exception to broaden scope.
- **PR shape:** continue the one existing PR #47 branch from `main`; no chain strategy, stacked PR, or new branch is planned.
- **Rough authored diff forecast:** approximately **900–1,500 changed authored lines (rough estimate; greater than 400)** across backend rules, frontend state, rollout safeguards/docs, and regression coverage. Count additions plus deletions; exclude generated output from the authored estimate. Re-estimate after one honest work-unit slicing pass; do not code-golf or delete tests/docs to fit a budget.
- The accepted exception is not permission to broaden scope. Keep each work-unit commit reviewable and preserve a focused cumulative PR story.

## Progress, evidence, and next step

**Overall status:** PMR-01 through PMR-04 parent spot checks and bounded fresh independent verification are complete. The final parent risk assessment failed closed because untracked `openspec/` required explicit inventory; treat the delivery tier as high/unassessable. RDD is off; no native review lifecycle/status was run, and no user-owned files were inspected. The latest payment implementation work-unit commit before this follow-up was `8adac76f560be2d66c2ec27604cc95f4a650d5e9`; the last previously pushed PR #47 head was `5c11d7eb5b485d2c0a86d1d62f851e074a0e0407`. The business-date correction is recorded below as `0bf6df951258c5ae0ca48ee584b0b1346a4d14a9`. Local `7519c991084463462201d17de5b6a52b955e8fe8` (`add agents.md`) was present on resume; this writer did not create or alter it. `openspec/` remains untracked and untouched. Local checks do not establish GitHub CI status; follow the authorized push and PR-check sequence below before marking the PR READY FOR REVIEW. No merge, deployment, production access, or production SQL execution has occurred.

| Task | Status | RED / GREEN / refactor evidence | Route evidence | Verification evidence | Commit ID |
|---|---|---|---|---|---|
| PMR-01 | implemented and committed; final verification complete | RED: provider-rate scale and four API regressions failed; GREEN/refactor: expanded backend payment suite, 75 passed | delegated direct; one writer, no child agents | final tier high/unassessable; parent money spot check 40 passed; independent coverage verification PASS | `efcfa467fbe5d340ec7f2bac27a62d40a6313bc6` |
| PMR-02 | original implementation and `cv=2` correction committed; final verification complete | Original strict parser/anchor/manual regressions plus corrective token writer RED/GREEN; focused backend 52 passed; full backend 333 passed | delegated direct; one writer, no child agents | final tier high/unassessable; parent token/readiness spot check 24 passed; independent token and coverage verification PASS | `d22dbd2b13891ea2b0ab6af6911977c80a0d0f5d` + correction `855580f8afb6bbb359cde224e6331315fcdd6032` |
| PMR-03 | original implementation and fail-closed preflight correction committed; final verification complete | Original migration coverage plus corrective READY/NOT_READY PostgreSQL cases; boundary 9 passed; full backend 333, concurrency, full-stack and aggregate passed | delegated direct; one writer, no child agents | final tier high/unassessable; parent checks 12 and 24 passed; independent schema, preflight, migration, rollback, Vercel, and legacy-v1 verification PASS | `40898896fd811e7b824e239c5d004261befd3af4` + correction `a3a369a6d6647a68cb0e17d5bf2bb9f2233533ac` |
| PMR-04 | implemented and committed; final verification complete | RED: CASE J fixture missing; GREEN: backend 1 focused regression and aggregate 330 backend + 4 Playwright tests passed | delegated direct; one writer, no child agents | final tier high/unassessable; parent lifecycle spot check 1 passed; independent supplemental coverage verification PASS | `ed39192e99b9ddff5524a70d1328b14944477a65` |
| PMR-01/04 coverage supplement | coverage-only follow-up complete; independent verification passed | RED: 38 tests / 2 future-date failures due to the unfixed test clock; GREEN: 40 focused tests and all final runners passed, including backend 335 and Playwright 4 | one writer; no production behavior changes | independent verification PASS for no-mutation overconversion, anchor rejection, bidirectional half-cent residuals, and fixed-Clock endpoint cases; parent checks complete | `8adac76f560be2d66c2ec27604cc95f4a650d5e9` (`test(payment): cover remaining money boundaries`), direct child of `908ed72e3911c0263534a43e49678b27474f535a` |

### Final verification record

- **Parent assessment and spot checks:** `gentle-ai review assess --cwd ... --json` failed closed because untracked `openspec/` required explicit inventory. The final tier is therefore high/unassessable. RDD is off; no native review lifecycle/status was run, and no user-owned untracked files were inspected. Parent spot checks passed: `./mvnw -Dtest=PaymentMoneyInvariantBoundaryTest,PaymentSubmissionSnapshotContractTest test` (12); after token/preflight correction, `./mvnw -Dtest=PaymentPreviewTokenServiceTest,PaymentMoneyInvariantBoundaryTest,PaymentSubmissionSnapshotContractTest test` (24); `./mvnw -Dtest=PaymentMoneyPolicyTest,PaymentRestControllerFreeAmountTest test` (40); and `./mvnw -Dtest=PaymentRestControllerFreeAmountTest#reviewPayment_partialCrossCurrencyAcrossInstallmentsConservesBothCurrenciesAndVoidReversesPersistedAllocations test` (1).
- **Fresh independent verification:** PMR-03/token checks passed: issuer uses exactly `cv=2`, tokens without `cv` are rejected, and the exact `v1` default plus validated constraint/shared readiness SQL fail closed for `v10`, a wrong same-name constraint, `NOT VALID`, and missing v2 cross-currency metadata. The backend preflight precedes container start; migration, rollback, Vercel ordering, and legacy v1 behavior were verified. PMR-01/02/04 coverage checks passed after the supplement: exact overconversion causes no mutation, later anchor is rejected while #1 remains valid, half-cent residuals are asserted in both currency directions, and fixed-Clock endpoint cases reject future dates for same- and cross-currency requests.
- **Backend:** final `cd backend && ./mvnw test` passed (335 tests).
- **Frontend:** `cd frontend && NODE_OPTIONS=--no-experimental-webstorage npm test` passed (27 files, 112 tests); `npm run build` passed; `npm run lint` reported 0 errors and 3 warnings in unchanged files. `npm ci` was not rerun because lockfiles were unchanged; the earlier run installed 402 packages and reported 25 advisories (4 low, 5 moderate, 15 high, 1 critical).
- **Integration and deployment checks:** `shellcheck scripts/test-payment-concurrency.sh scripts/test-payment-full-stack.sh scripts/test-payment-money-invariants.sh` passed. The concurrency harness passed its same-currency review/void cases and 2 cross-currency tests; transient readiness `curl: (52)` responses retried successfully. The full-stack harness passed all 4 Playwright tests. `./scripts/test-payment-money-invariants.sh` passed with the same final backend, frontend, build/lint, concurrency, and browser layers. `docker compose --env-file .env.example config --no-env-resolution --quiet` passed. Docker Hub images were pulled anonymously only after explicit user authorization. Generated `frontend/test-results` output was removed.
- **Boundary:** all verification artifacts in this earlier final-verification record remained local. No production SQL or live/production database access was used. GitHub CI had not run for local commits after the then-remote PR head; local verification did not establish remote CI status.

### Work-unit commit evidence

Original implementation commit identities and counts are preserved below. The final parent assessment was unassessable as recorded above; each bounded parent spot check and independent verification result is recorded in the PMR evidence sections.

| Task | Commit / parent SHA | Conventional Commit message | Focused test command + exact result | Runtime harness + exact result / N/A reason | Rollback boundary | Authored additions + deletions |
|---|---|---|---|---|---|---|
| PMR-01 | `efcfa467fbe5d340ec7f2bac27a62d40a6313bc6` / parent `efbbe1c746f2e3162600f0cf11e7ec9a1c08fa17` | fix(payment): enforce safe monetary limits and rate identity | RED `./mvnw -Dtest=PaymentMoneyPolicyTest test` — 9 run, 1 expected failure; integration RED `./mvnw -Dtest=PaymentMoneyPolicyTest,PaymentRestControllerFreeAmountTest test` — 33 run, 4 expected failures; GREEN/refactor expanded backend payment suite — 75 run, 0 failures/errors/skips | N/A for this unit: focused Testcontainers/PostgreSQL integration covers persistence-sensitive behavior; isolated full-stack/concurrency harnesses are reserved for PMR-04 | `PaymentMoneyPolicy`, `PaymentService`, `PaymentAllocationPlanner`, `PaymentPreviewTokenService`, business-date policy/configuration, calculation DTO, and PMR-01 regression tests | 852 additions + 77 deletions |
| PMR-02 | `d22dbd2b13891ea2b0ab6af6911977c80a0d0f5d` / parent `7519c991084463462201d17de5b6a52b955e8fe8`; correction `855580f8afb6bbb359cde224e6331315fcdd6032` / parent `276073ba542514ba79acaaa3cf05c61840f009ee` | fix(frontend): preserve strict payment review and input state; fix(payment): enforce preview token v2 compatibility | RED frontend parser: 5 run/2 expected failures; token writer RED: 1 expected failure because issuer emitted `cv=3`; token/controller GREEN: 52 passed; full backend: 333 passed | N/A for correction: token/controller integration is covered by Testcontainers; full-stack lifecycle harness is PMR-04 | Token writer, token tests, API response/persistence fixtures, and versioned contract docs; original PMR-02 changes remain in their original commit | Original: 412 additions + 79 deletions; correction: 37 additions + 22 deletions |
| PMR-03 | `40898896fd811e7b824e239c5d004261befd3af4` / parent `d22dbd2b13891ea2b0ab6af6911977c80a0d0f5d`; correction `a3a369a6d6647a68cb0e17d5bf2bb9f2233533ac` / parent `855580f8afb6bbb359cde224e6331315fcdd6032` | fix(deploy): gate payment backend on schema readiness; fix(deploy): validate payment schema readiness exactly | RED broad default match: 1 expected failure; READY/NOT_READY Testcontainers regression: 1 passed; boundary class: 9 passed; full backend: 333 passed; ShellCheck and Compose config passed | Aggregate harness passed: backend 333; frontend 27 files/112 tests, build/lint; isolated same/cross-currency concurrency; 4 Playwright cases. Production preflight was not executed against any database. | Shared read-only readiness predicate and preflight runner; application-only rollback remains required, never shrink schema or rewrite financial history | Original: 301 additions + 49 deletions; correction: 190 additions + 59 deletions |
| PMR-04 | `ed39192e99b9ddff5524a70d1328b14944477a65` / parent `40898896fd811e7b824e239c5d004261befd3af4` | test(payment): prove lifecycle monetary invariants | RED `./scripts/test-payment-full-stack.sh` — 3 passed, 1 expected CASE J missing-fixture failure; GREEN — 4 passed; focused backend multi-installment regression — 1 passed; aggregate script — backend 330, frontend 27/112, build/lint, concurrency and 4 browser tests passed | Full-stack lifecycle and isolated concurrency runs use loopback and disposable Postgres; no live/production database or external FX | Test-only CASE J seeding/browser scenario and backend multi-installment outcome regression; rollback removes only these tests/fixture changes | 311 additions + 12 deletions |
| PMR-01/04 coverage supplement | `8adac76f560be2d66c2ec27604cc95f4a650d5e9` / parent `908ed72e3911c0263534a43e49678b27474f535a` | test(payment): cover remaining money boundaries | RED `./mvnw -Dtest=PaymentMoneyPolicyTest,PaymentRestControllerFreeAmountTest test` — 38 tests, 2 expected future-date failures with the system clock; GREEN — same command, 40 passed with fixed payment Clock and added coverage | `./scripts/test-payment-full-stack.sh` — 4 Playwright tests passed; aggregate and concurrency harnesses passed as recorded above. Test-only supplement; no production behavior changed. | Supplemental monetary-boundary assertions and fixed-Clock regression fixtures in the coverage-only test changes; no production behavior or schema rollback | 217 additions + 49 deletions |
| CI-only business-date fixture correction | `0bf6df951258c5ae0ca48ee584b0b1346a4d14a9` / parent `5c11d7eb5b485d2c0a86d1d62f851e074a0e0407` | test(payment): stabilize business-date fixtures | RED under `TZ=UTC`: 6 focused tests, 2 failures + 4 errors; GREEN under `TZ=UTC`: same 6 tests passed; full clean backend suite: 335 passed | `./scripts/test-payment-concurrency.sh` passed same-currency review/void and 2 cross-currency tests; full-stack passed 4 Playwright tests; aggregate `./scripts/test-payment-money-invariants.sh` passed | Revert only the five test fixtures and the isolated concurrency-script date fixture; no production behavior/schema change | 34 additions + 21 deletions |

**Next step / delivery boundary:** push only `fix/payment-money-invariants` using the explicitly authorized configured Git transport, then verify the required PR #47 checks for the resulting head. Local tests alone do not establish GitHub CI green. Do not merge, deploy/promote, access the VPS/production environment, or execute SQL. Do not claim READY FOR REVIEW until the required remote checks are terminal and green.

## CI-only business-date test follow-up

**Outcome:** The CI failures were test-fixture timezone drift, not a production payment-date defect. Production date validation was not changed.

### Failure evidence

- GitHub Actions run `35937721423` failed on PR head `5c11d7eb5b485d2c0a86d1d62f851e074a0e0407`; the supplied CI evidence is dated **2026-09-23** and does not include an exact time-of-day. Backend Tests and Payment Money Invariants failed, Frontend Build passed, and Deploy Backend to VPS was skipped.
- All six failures used `2026-09-24` while the policy reported the Buenos Aires business date as `2026-09-23`:

  | Test | CI evidence |
  |---|---|
  | `PaymentRestControllerTest.previewRegisterReload_preservesQuoteIdentityWithoutProviderRefetch` | Expected HTTP 200; received 400. |
  | `TripUnassignFinancialIntegrityIntegrationTest.deleteStudent_productionRegisterPaymentPath_returns409AndPreservesEverything` | Expected HTTP 201; received 400. |
  | `PaymentRestControllerTest.decimalStrings_preservePersistedScaleAcrossHistoryAndPendingReview` | Registration rejected with “La fecha de pago no puede ser futura (hoy es 2026-09-23)”. |
  | `PaymentRestControllerTest.quoteIdentity_survivesReloadFullPartialReviewAndVoid` | Registration rejected with the same future-date validation. |
  | `PaymentRestControllerTest.voidReversesPersistedAllocationsExactlyWithoutRewritingApprovedHistory` | Registration rejected with the same future-date validation. |
  | `ConcurrentFinancialIntegrityIntegrationTest.registration_preservesTripThenScopedInstallmentsLockOrderAgainstUnassign` | Registration rejected with the same future-date validation. |

### Cause and correction

`LocalDate.now()` in the integration fixtures and `jq now` in the isolated concurrency script used the host/UTC date. Around UTC midnight that date advanced to 2026-09-24 before the `America/Argentina/Buenos_Aires` business date advanced, so valid test registrations were rejected as future-dated.

The test-only correction uses the fixed historical payment date `2020-01-15` for registration and payment-history fixtures in `PaymentRestControllerTest`, `TripUnassignFinancialIntegrityIntegrationTest`, `ConcurrentFinancialIntegrityIntegrationTest`, `TripRestControllerTest`, and `UserRestControllerTest`. Cross-currency quote fixtures and their request/stub dates use the same requested date. The isolated concurrency script now submits that same deterministic historical date instead of deriving it from `jq now`. No production source or date policy changed.

### RED/GREEN and verification

- On the host’s Buenos Aires timezone, the six focused tests initially passed; `TZ=UTC` reproduced the CI defect locally on **2026-09-24T00:28Z**: 6 tests, 2 assertion failures and 4 errors, all showing `today=2026-09-23` versus request date `2026-09-24`.
- After the fixture correction, the same focused `TZ=UTC ./mvnw -Dtest=PaymentRestControllerTest#previewRegisterReload_preservesQuoteIdentityWithoutProviderRefetch+quoteIdentity_survivesReloadFullPartialReviewAndVoid+decimalStrings_preservePersistedScaleAcrossHistoryAndPendingReview+voidReversesPersistedAllocationsExactlyWithoutRewritingApprovedHistory,TripUnassignFinancialIntegrityIntegrationTest#deleteStudent_productionRegisterPaymentPath_returns409AndPreservesEverything,ConcurrentFinancialIntegrityIntegrationTest#registration_preservesTripThenScopedInstallmentsLockOrderAgainstUnassign test` passed: 6 tests, 0 failures/errors/skips.
- The first non-clean `TZ=UTC ./mvnw test` attempt stopped before running tests because stale generated classes included `TestcontainersConfiguration 2`; `TZ=UTC ./mvnw clean test` then passed all 335 backend tests. The aggregate `./scripts/test-payment-money-invariants.sh` subsequently passed its backend suite (335), frontend suite (27 files / 112 tests), build, lint, concurrency, and all 4 Playwright cases.
- The isolated concurrency script first exposed its own dynamically generated future payment date (HTTP 400); after replacing it with the historical test date, `./scripts/test-payment-concurrency.sh` passed same-currency review/void (one success and one conflict each, exact single credit/reversal) and its 2 cross-currency Testcontainers tests. `./scripts/test-payment-full-stack.sh` passed all 4 Playwright cases.
- `NODE_OPTIONS=--no-experimental-webstorage npm test` passed (27 files / 112 tests); `npm run build` passed; `npm run lint` reported 0 errors and 3 existing warnings. `npm ci` was not rerun because lockfiles are unchanged and it passed earlier. `shellcheck scripts/test-payment-concurrency.sh scripts/test-payment-full-stack.sh scripts/test-payment-money-invariants.sh`, `docker compose --env-file .env.example config --no-env-resolution --quiet`, and `git diff --check` passed.
- Generated `frontend/test-results/.last-run.json` output was removed. `openspec/` remains untracked and untouched.

### Correction commit and remote boundary

- Test-fixture correction commit: `0bf6df951258c5ae0ca48ee584b0b1346a4d14a9` / parent `5c11d7eb5b485d2c0a86d1d62f851e074a0e0407`; `test(payment): stabilize business-date fixtures`.
- This local verification record does **not** claim green GitHub CI. After pushing the authorized branch, confirm Backend Tests, Frontend Build, and Payment Money Invariants for the resulting PR head; observe any automatic Vercel PR preview only. VPS deployment remains skipped. No manual deployment/promotion, merge, production access, or SQL execution.
