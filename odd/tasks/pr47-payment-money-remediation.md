# PR #47 Payment Money Remediation

## Objective

Close the verified payment-money correctness and rollout gaps on PR #47 while preserving the existing payment API intent, audited financial history, and deployment safety. The defects allow a rounded reverse-FX suggestion to exceed the remaining balance, invalid approval input to fall back to the full reported amount, selected-installment input to drift to enrollment-wide debt, and a schema migration to be bypassed by automatic backend deployment.

## Baseline and authorization

| Item | Baseline |
|---|---|
| Worktree / branch | `/Users/joseslavkis/Documents/Gestion_pagos_viajes` / `fix/payment-money-invariants` |
| Review candidate | PR #47; head `efbbe1c746f2e3162600f0cf11e7ec9a1c08fa17` (verified in task authorization) |
| Base | `main@cb3d8f4ec8d51e460c548154e35bd51efb4ceb7b` |
| Tracking issue | #46, approved and linked to PR #47 |
| Delivery boundary | Keep the existing PR branch and single PR. Local work-unit commits are authorized for implementation; do not push in this task. |

**Implementation status:** delegated direct implementation is underway on the existing branch, with local work-unit commits authorized. Do not push or perform production operations.

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

**Status:** implemented and committed; parent verification pending

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

**Verification evidence:** record the actual risk tier and writer profile, the parent's post-completion risk assessment and one spot-check result, and any independent verifier required by the tier rule above. If no separate verifier is required, record `N/A` with the tier/profile reason. Do not launch receipt-based reviews or native lenses while RDD is `off`.

**Progress:** implementation committed as `efcfa467fbe5d340ec7f2bac27a62d40a6313bc6`. RED `./mvnw -Dtest=PaymentMoneyPolicyTest test`: 9 tests, 1 failure as expected (`1E+3` retained scale -3 instead of canonical scale 0). Integration RED `./mvnw -Dtest=PaymentMoneyPolicyTest,PaymentRestControllerFreeAmountTest test`: 33 tests, 4 expected failures for same/cross-currency future dates, sub-cent approval, and anchor-vs-total REMAINING. GREEN/refactor rerun `./mvnw -Dtest=PaymentMoneyPolicyTest,PaymentAllocationPlannerTest,PaymentPreviewTokenServiceTest,PaymentBusinessDatePolicyTest,PaymentRestControllerFreeAmountTest,PaymentRestControllerTest,PaymentSubmissionSnapshotContractTest test`: 75 tests, 0 failures/errors/skips. CASE G keeps the 10.16 ARS suggested amount separate from its 15.23 ARS safe maximum. Parent risk assessment and spot check remain pending; conditional independent verifier depends on the parent's tier assessment.

**Conventional Commit placeholder:** `fix(payment): enforce safe monetary limits and rate identity`

### PMR-02 — Preserve frontend approval and payment input state

**Status:** implementation, focused verification, and work-unit commit complete; parent verification pending

**Intent:** make approval and amount-entry state explicit so malformed edits cannot approve more than intended and selected-installment context is not replaced by broader debt.

**Acceptance criteria:**

- The review parser strictly rejects `250.`, `abc`, `-1`, and `1.005` without issuing a request or mutating state; it preserves the entered text and disables Save. `0` remains valid for total rejection. Approval above the reported amount is rejected.
- `REMAINING` means the current balance of the validated first pending anchor installment, never the enrollment-wide sum. For `MANUAL`, keep `totalPendingAmountInTripCurrency` separate from the maximum payment amount. A manual amount of 500 across installments of 240 + 240 + 240 allocates 240 + 240 + 20.
- The backend derives monetary authority, pending totals, safe maxima, and allocations from validated persisted context. The frontend must not provide authoritative totals, FX conversions, maximums, or allocations.
- A failed or stale calculation does not overwrite newer user input; manual edits remain visible and are not silently relabeled or replaced.
- Frontend tests cover valid partial approval, total rejection at zero, over-reported approval, invalid/incomplete edits, selected-installment `REMAINING`, the `MANUAL` waterfall vector, and stale/manual-input preservation without introducing JavaScript floating-point authority for business decisions.

**Implementation route:** add component/hook tests for the invalid fallback and installment-context regressions; capture RED; tighten parsing and state/request context handling at the existing payment UI boundary; capture GREEN; refactor while preserving accessible validation feedback and the entered value.

**Verification:** run `cd frontend && NODE_OPTIONS=--no-experimental-webstorage npm test`, with focused assertions that the four invalid strings cause no request, text remains unchanged, Save is disabled, zero rejection remains actionable, and anchor/manual calculations keep their distinct meanings. Then run frontend build and lint.

**Route evidence:** `delegated direct` to one bounded writer. Trigger: this unit changes 2+ non-trivial frontend component/hook and regression-test files; broad inspection/mapping is delegated to `explore`, and preparation reads belong to the writer.

**Verification evidence:** record the actual risk tier and writer profile, the parent's post-completion risk assessment and one spot-check result, and any independent verifier required by the tier rule above. If no separate verifier is required, record `N/A` with the tier/profile reason. Do not launch receipt-based reviews or native lenses while RDD is `off`.

**Progress:** implementation verified locally; PMR-02 work-unit commit pending. RED `NODE_OPTIONS=--no-experimental-webstorage npx vitest run src/features/payments/pages/__tests__/PendingReviewPage.test.tsx`: 5 tests, 2 failures because Save remained enabled for invalid and over-reported values; GREEN rerun: 5 passed. RED `NODE_OPTIONS=--no-experimental-webstorage npx vitest run src/features/users/pages/__tests__/UserDashboardPage.test.tsx -t "seeds REMAINING input"`: 1 failure (input showed 200 instead of server balance 199.99); GREEN rerun after canonical remaining wiring: 1 passed. Backend endpoint RED `./mvnw -Dtest=PaymentRestControllerFreeAmountTest#myInstallments_returnsCanonicalServerComputedRemainingBalance test`: 1 missing-field failure; after local Docker Desktop was started, GREEN rerun: 1 passed. DTO naming RED `./mvnw -Dtest=PaymentCalculationResponseDTOTest test`: 1 expected failure because JSON exposed `remainingAmount`; GREEN `./mvnw -Dtest=PaymentCalculationResponseDTOTest,PaymentUserInstallmentDTOTest test`: 2 passed. Manual input RED `NODE_OPTIONS=--no-experimental-webstorage npx vitest run src/features/users/pages/__tests__/UserDashboardPage.test.tsx -t "does not create an enabled calculation query for a sub-cent manual amount"`: 1 failure because the query cache contained an enabled `MANUAL` query for `1.005`; GREEN rerun after cent-strict payload normalization: 1 passed. Latest focused frontend run over `PendingReviewPage.test.tsx`, `UserDashboardPage.test.tsx`, `payments-service.test.tsx`, and `payments-dtos.test.ts`: 4 files, 31 tests passed. Latest focused backend run `./mvnw -Dtest=PaymentRestControllerFreeAmountTest,PaymentCalculationResponseDTOTest,PaymentUserInstallmentDTOTest test`: 29 tests passed. An earlier API test attempt failed to load the Spring context while Docker was stopped; local Docker was started, `docker info` then reported Server 29.7.2 / Linux arm64, and the focused Testcontainers run passed. Full suites/build/lint and payment harnesses remain for final verification. Parent risk assessment, spot check, and conditional verifier remain pending.

**Verification update:** `cd frontend && npm ci` passed (402 packages installed; npm reported 25 dependency advisories and install scripts awaiting approval). `cd frontend && NODE_OPTIONS=--no-experimental-webstorage npm test` passed: 27 files, 112 tests. `cd frontend && npm run build` passed. `cd frontend && npm run lint` passed with 0 errors and 3 warnings in unmodified TokenContext/session files. The full backend suite and isolated payment harnesses remain for final verification.

**Commit evidence:** local commit `d22dbd2b13891ea2b0ab6af6911977c80a0d0f5d`, parent `7519c991084463462201d17de5b6a52b955e8fe8`; `fix(frontend): preserve strict payment review and input state`. Change size: 412 additions, 79 deletions. Parent risk assessment and spot check remain pending.

### PMR-03 — Guard migration rollout and document operations

**Status:** implementation, focused/full-backend verification, aggregate verification, and work-unit commit complete; parent review pending

**Intent:** prevent a backend requiring the payment schema change from being deployed before the migration is confirmed, while keeping historical data and the existing deployment safety model intact.

**Acceptance criteria:**

- The additive migration is transactional and idempotent. Disposable PostgreSQL tests start from the old schema with historical rows, verify preserved values after migration, prove a second run is a no-op, start the migrated schema with Hibernate `ddl-auto=validate`, and persist/reload an 8-scale rate.
- An older writer that omits `calculation_version` cannot create a NULL version: backfill existing NULLs to `v1` in the explicit migration, then enforce `DEFAULT 'v1'` and `NOT NULL` so legacy inserts that omit the new column receive `v1` and explicit NULL is rejected. Test both insert shapes. This remains an operator-run schema migration; do not add startup migration, automatic production backfill, or Hibernate `ddl-auto=update` behavior.
- Legacy `v1` pending submissions remain reviewable from their stored snapshot without provider refetch or reconstruction. All preview tokens issued under the old calculation version become invalid immediately; the user must recalculate before registration.
- Production profile configuration uses `ddl-auto=validate`. Deployment has an explicit compatibility precondition for the exact release and fails before backend container/application mutation when the schema is incompatible. It does not silently execute production SQL. Exact-SHA deployment, stale-deploy protection, deployment serialization, concurrency safeguards, and existing CI prerequisites remain intact.
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

**Verification evidence:** risk tier high because this work adds a production financial-schema constraint and blocks backend deployment on its validation; writer profile `gpt-6-luna`. Parent spot-check passed: verified migration default/backfill/NOT NULL order, both mock preflight branches, and exact-SHA → preflight → backend workflow ordering. Independent verification remains pending because high risk requires it; no child agent or remote reviewer was authorized.

**Progress:** implementation, commit, and aggregate verification complete; parent review remains pending. RED: `PaymentSubmissionSnapshotContractTest#persistenceDefaultsMissingCalculationVersionToLegacyV1` failed as expected (`v1` expected, `null` observed). GREEN/refactor: `./mvnw -Dtest=PaymentMoneyInvariantBoundaryTest,PaymentSubmissionSnapshotContractTest test` — 11 tests, 0 failures/errors/skips, including disposable PostgreSQL migration idempotence, historical preservation, omitted-column default, explicit-NULL rejection, and Hibernate validation. Process-level readiness regression: `./mvnw -Dtest=PaymentMoneyInvariantBoundaryTest#schemaPreflight_failsClosedForIncompatibleSchema test` — 1 passed for both `NOT_READY` and `READY` mock-Docker results. Full backend: `./mvnw clean test` — 329 tests, 0 failures/errors/skips. Aggregate `./scripts/test-payment-money-invariants.sh` passed: backend 329 tests; frontend 27 files/112 tests, build passed, lint 0 errors/3 pre-existing warnings; isolated same-currency review/void each produced exactly one success and one conflict and reversed the 100.00 credit; 2 cross-currency concurrency tests passed; all 3 Playwright CASE F/G tests passed. Compose validation: `docker compose --env-file .env.example config --no-env-resolution --quiet` passed; `bash -n scripts/check-payment-schema-readiness.sh` passed and the script is executable. A first unclean `./mvnw test` could not start tests because stale duplicate `TestcontainersConfiguration 2/3.class` files existed under `target`; `clean test` removed the stale classes and passed. The production preflight itself was not run against any database. Commit `40898896fd811e7b824e239c5d004261befd3af4`, parent `d22dbd2b13891ea2b0ab6af6911977c80a0d0f5d`: `fix(deploy): gate payment backend on schema readiness`. Parent risk assessment, spot check, and any tier-required independent verification remain pending.

**Conventional Commit placeholder:** `fix(deploy): gate payment backend on schema readiness`

### PMR-04 — Prove payment lifecycle and invariant coverage

**Status:** implementation and aggregate verification passed; work-unit commit and parent review pending

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

**Verification evidence:** risk tier medium because PMR-04 changes only isolated regression fixtures/tests, not production behavior or schema; writer profile `gpt-6-luna` (not small). Parent spot-check passed: verified CASE J uses deterministic rate `1234.56`, splits the 1.00 USD persisted conversion across two installments, checks the .50 USD approval and 617.28 ARS persisted allocation/rejected residual, then confirms void restores both balances. Independent verifier `N/A` per the medium-tier/non-small-writer rule. Do not launch receipt-based reviews or native lenses while RDD is `off`.

**Progress:** RED `./scripts/test-payment-full-stack.sh` — the 3 existing CASE F/G tests passed; CASE J failed as expected because the fixture lacked its two-installment trip. GREEN `./scripts/test-payment-full-stack.sh` — 4 Playwright tests passed, including browser registration, persisted/reloaded snapshot, admin partial cross-currency review, spreadsheet UI void, and paid-balance reversal. Focused backend `./mvnw -Dtest=PaymentRestControllerFreeAmountTest#reviewPayment_partialCrossCurrencyAcrossInstallmentsConservesBothCurrenciesAndVoidReversesPersistedAllocations test` — 1 passed against disposable Testcontainers PostgreSQL; approved/rejected outcomes conserve reported and trip-currency values across two installments, and void reverses the persisted allocation. Final `./scripts/test-payment-money-invariants.sh` passed: backend 330 tests; frontend 27 files/112 tests; frontend build passed; lint passed with 0 errors/3 pre-existing warnings; isolated same-currency review/void and cross-currency concurrency checks passed; all 4 Playwright CASE F/G/J tests passed. An extra standalone E2E `tsc` check was attempted but the repository has no `@types/node`; its existing E2E node imports/process globals are not part of `tsconfig.json`'s build references. Parent risk assessment/spot check, conditional verifier, and commit remain pending.

**Conventional Commit placeholder:** `test(payment): prove lifecycle monetary invariants`

## Delivery strategy and review forecast

- **Strategy:** `exception-ok` — the existing PR #47 body explicitly records that the cumulative change exceeds the 400-line review budget and that the previously accepted single-PR `size:exception` applies. Do not use this exception to broaden scope.
- **PR shape:** continue the one existing PR #47 branch from `main`; no chain strategy, stacked PR, or new branch is planned.
- **Rough authored diff forecast:** approximately **900–1,500 changed authored lines (rough estimate; greater than 400)** across backend rules, frontend state, rollout safeguards/docs, and regression coverage. Count additions plus deletions; exclude generated output from the authored estimate. Re-estimate after one honest work-unit slicing pass; do not code-golf or delete tests/docs to fit a budget.
- The accepted exception is not permission to broaden scope. Keep each work-unit commit reviewable and preserve a focused cumulative PR story.

## Progress, evidence, and next step

**Overall status:** in progress. PMR-01 through PMR-03 are committed and PMR-03 verification is green; parent reviews remain pending. PMR-04 implementation and aggregate verification are green; its commit and parent review remain pending. Local commit `7519c991084463462201d17de5b6a52b955e8fe8` (`add agents.md`) was present on resume; this writer did not create or alter it. `openspec/` remains untracked and untouched. No push, merge, deployment, production access, or production SQL execution has occurred.

| Task | Status | RED / GREEN / refactor evidence | Route evidence | Verification evidence | Commit ID |
|---|---|---|---|---|---|
| PMR-01 | implemented and committed | RED: provider-rate scale and four API regressions failed; GREEN/refactor: expanded backend payment suite, 75 passed | delegated direct; one writer, no child agents | parent risk assessment, spot check, and conditional verifier pending | `efcfa467fbe5d340ec7f2bac27a62d40a6313bc6` |
| PMR-02 | implemented and committed | RED/GREEN for strict review parser, canonical anchor balance, manual cent parser, backend canonical balance, and explicit anchor field; full frontend suite and focused backend suite passed | delegated direct; one writer, no child agents | parent risk assessment, spot check, and conditional verifier pending | `d22dbd2b13891ea2b0ab6af6911977c80a0d0f5d` |
| PMR-03 | implemented and committed | RED for legacy `v1` fallback; focused tests, full backend 329, and aggregate harness passed | delegated direct; one writer, no child agents | high risk; parent spot-check passed; independent verification pending | `40898896fd811e7b824e239c5d004261befd3af4` |
| PMR-04 | implementation and aggregate verification passed; commit pending | RED: CASE J fixture missing; GREEN: backend 1 focused regression and aggregate 330 backend + 4 Playwright tests passed | delegated direct; one writer, no child agents | medium risk; parent spot-check passed; independent verifier N/A per tier/profile | pending |

### Work-unit commit evidence placeholders

Fill one row per work unit after implementation; do not mark a task complete before recording the evidence. Record the focused test command and exact result, runtime harness command/result or explicit `N/A` with reason, parent risk assessment and post-completion spot-check result, any independent verifier result or `N/A` per the tier rule, rollback boundary, commit ID, and diff count.

| Task | Commit / parent SHA | Conventional Commit message | Focused test command + exact result | Runtime harness + exact result / N/A reason | Rollback boundary | Authored additions + deletions |
|---|---|---|---|---|---|---|
| PMR-01 | `efcfa467fbe5d340ec7f2bac27a62d40a6313bc6` / parent `efbbe1c746f2e3162600f0cf11e7ec9a1c08fa17` | fix(payment): enforce safe monetary limits and rate identity | RED `./mvnw -Dtest=PaymentMoneyPolicyTest test` — 9 run, 1 expected failure; integration RED `./mvnw -Dtest=PaymentMoneyPolicyTest,PaymentRestControllerFreeAmountTest test` — 33 run, 4 expected failures; GREEN/refactor expanded backend payment suite — 75 run, 0 failures/errors/skips | N/A for this unit: focused Testcontainers/PostgreSQL integration covers persistence-sensitive behavior; isolated full-stack/concurrency harnesses are reserved for PMR-04 | `PaymentMoneyPolicy`, `PaymentService`, `PaymentAllocationPlanner`, `PaymentPreviewTokenService`, business-date policy/configuration, calculation DTO, and PMR-01 regression tests | 852 additions + 77 deletions |
| PMR-02 | `d22dbd2b13891ea2b0ab6af6911977c80a0d0f5d` / parent `7519c991084463462201d17de5b6a52b955e8fe8` | fix(frontend): preserve strict payment review and input state | RED `NODE_OPTIONS=--no-experimental-webstorage npx vitest run src/features/payments/pages/__tests__/PendingReviewPage.test.tsx` — 5 run, 2 expected failures; GREEN — 5 passed. Anchor/manual/dashboard component and DTO regressions GREEN. `NODE_OPTIONS=--no-experimental-webstorage npm test` — 27 files/112 passed; `npm run build` passed; `npm run lint` passed with 0 errors/3 warnings. Backend focused — 29 tests passed | N/A for this unit: component/API integration checks run; full-stack lifecycle harness is PMR-04 | `PaymentService.java`, DTOs, controller tests, and PMR-02 frontend files | 412 additions + 79 deletions |
| PMR-03 | `40898896fd811e7b824e239c5d004261befd3af4` / parent `d22dbd2b13891ea2b0ab6af6911977c80a0d0f5d` | fix(deploy): gate payment backend on schema readiness | RED legacy `v1` fallback — 1 expected failure (`null`); GREEN focused 11 plus process-level preflight 1 passed; `./mvnw clean test` — 329 passed; Compose and shell syntax passed | `./scripts/test-payment-money-invariants.sh` — 329 backend tests, 27 frontend files/112 tests, build/lint passed (3 existing warnings), isolated same- and cross-currency concurrency checks passed, 3 Playwright tests passed. Production schema preflight was not executed against a live DB. | Additive schema default/NOT NULL and corresponding startup/deploy guard; roll back application artifacts only and keep schema widening/default/constraint | 301 additions + 49 deletions |
| PMR-04 | pending | test(payment): prove lifecycle monetary invariants | RED `./scripts/test-payment-full-stack.sh` — 3 passed, 1 expected CASE J missing-fixture failure; GREEN — 4 passed; focused `./mvnw -Dtest=PaymentRestControllerFreeAmountTest#reviewPayment_partialCrossCurrencyAcrossInstallmentsConservesBothCurrenciesAndVoidReversesPersistedAllocations test` — 1 passed; `./scripts/test-payment-money-invariants.sh` — backend 330, frontend 27/112, build/lint, concurrency and 4 browser tests passed | Full-stack lifecycle and isolated concurrency runs use loopback and disposable Postgres; no live/production database or external FX | Test-only CASE J seeding/browser scenario and backend multi-installment outcome regression; rollback removes only these tests/fixture changes | pending |

**Next step:** inspect the final PMR-04/ODD diff, complete the parent risk/spot checks, and commit PMR-04 on the existing branch. Continue strict RED → GREEN → REFACTOR. Keep all implementation local; do not push or make remote changes.
