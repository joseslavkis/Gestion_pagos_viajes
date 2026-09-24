# AGENTS.md

## Purpose

This file is the repository-level guide for coding agents. Keep it short and durable: use it as a map, not as a substitute for reading the relevant code, tests, README, or task-specific docs.


Before changing code:
1. Read the files directly involved in the task and their tests.
2. Check nearby conventions before introducing a new pattern.
3. Prefer the smallest change that fully solves the requested problem.
4. Do not broaden scope with unrelated refactors or cleanup.

## Repository map

- `backend/`: Java 21 + Spring Boot + Maven + PostgreSQL.
  - Main package: `com.agencia.pagos`
  - Package-by-feature layout: `payment`, `trip`, `user`, `school`, `contact`, `infrastructure`, `shared`, `config`.
- `frontend/`: React 19 + TypeScript + Vite.
  - Feature code lives mainly under `src/features/`.
  - Shared API/utilities live under `src/lib/` and `src/config/`.
- `scripts/`: operational and isolated verification scripts.
- `.github/workflows/ci-cd.yml`: CI and VPS deployment.
- `docker-compose.yml`: local/VPS service composition.
- `README.md`: product behavior and operational context.
- `docs/`: task-specific investigations/design notes when present.

Do not reorganize package/folder structure unless the task explicitly requires it.

## Core commands

Run only what is relevant while iterating, then run the required final checks for the changed area.

### Backend

```bash
cd backend
./mvnw test
```

For focused work, prefer targeted Maven tests first, then the full suite.

### Frontend

```bash
cd frontend
npm ci
npx vitest run
npm run build
npm run lint
```

Do not replace `npm ci` with `npm install` for CI-style verification.

### Docker / integration

Validate Compose changes with:

```bash
docker compose config
```

For payment/concurrency-sensitive changes, run:

```bash
./scripts/test-payment-concurrency.sh
```

This script is intentionally isolated. Do not weaken its isolation or point it at production resources.

## Engineering rules

### General

- Follow existing patterns before creating abstractions.
- Keep diffs focused and reviewable.
- Do not add production dependencies unless they provide clear value and the task requires them.
- Do not hide errors with `|| true`, broad catches, silent fallbacks, or fake success paths.
- Preserve public/API behavior unless the task explicitly changes the contract.
- When changing an API DTO, update backend DTOs, frontend schemas/types, callers, and tests together.
- User-facing text is Spanish; keep it clear and non-technical.

### Backend

- Use `BigDecimal` for money. Never use `float`/`double` as financial authority.
- Make rounding and scale explicit at financial boundaries.
- Keep transaction and locking behavior intentional. Do not change lock order casually.
- Do not solve architectural concerns by shuffling packages or wrapping dependencies only to reduce constructor size.
- Prefer behavior-focused extraction when a service genuinely mixes responsibilities.

### Frontend

- Keep backend contracts validated through the existing Zod/type layer.
- Avoid duplicating financial/business rules in JavaScript when the backend can be authoritative.
- Do not let stale async responses overwrite newer user state.
- Preserve manual user input unless the product flow explicitly requires replacing it.
- Prefer existing React Query / Testing Library / MSW patterns.

## Financial integrity

Payments are audit-sensitive. Treat these as invariants, not UI details:

- Approved financial state must reconcile with persisted allocations.
- `paidAmount` changes must be explainable by the allocations/movements that produced them.
- Cross-currency calculations must use one explicit exchange-rate snapshot for the operation.
- Do not silently clamp, discard, or hide monetary residuals.
- Do not round away legitimate cents.
- Voids/reversals should reverse the persisted credited amounts, not recalculate them from a new rate.
- Never silently rewrite approved financial history.

When a financial rule is ambiguous, stop and identify the business decision instead of inventing accounting semantics.

## Database and persistence

- PostgreSQL behavior matters; use PostgreSQL-backed integration tests for persistence-sensitive fixes when appropriate.
- Base Hibernate schema handling must remain safe. Do not introduce destructive `ddl-auto=create` behavior.
- Do not rely on Hibernate auto-DDL as a production migration strategy.
- For production schema changes, provide an explicit, reviewable migration/rollout path appropriate to the repository.
- Preserve existing data unless the task explicitly requires a migration or deletion policy.

## Receipt/file storage

Receipt storage spans database state and filesystem state.

- Do not expose receipt files as permanently public assets.
- Preserve tokenized/authenticated access semantics.
- Consider rollback/crash paths when changing file lifecycle code.
- Avoid creating orphan files or DB references through partial failure handling.

## Tests

A behavior change should normally come with a regression test.

Prefer:
- unit tests for pure rules/calculations;
- Spring integration tests for transactions/JPA/PostgreSQL behavior;
- frontend component tests for UI state and request timing;
- isolated scripts/E2E for cross-service, concurrency, or deployment-sensitive behavior.

Tests must be deterministic:
- no live FX values;
- no production services;
- no production DB;
- no real email sending;
- no dependence on current wall-clock behavior when a fixed value can be used.

Do not write tests that merely encode a known bug as acceptable behavior.

## CI and deployment

The current deployment flow intentionally deploys the exact SHA validated by CI.

Do not reintroduce deployment patterns equivalent to:

```bash
git reset --hard origin/main
```

as the deployment target after tests have already run.

Do not weaken:
- backend tests;
- frontend tests/build;
- deploy prerequisites;
- stale-deploy protection;
- deployment serialization.

Do not connect to or mutate the VPS/production environment unless the user explicitly asks for an operational action.

## Security and secrets

- Never print, commit, or paste `.env` contents, passwords, JWT secrets, SSH keys, API keys, or TLS private keys.
- Use `.env.example` only for non-secret examples/placeholders.
- Do not weaken auth, CORS, TLS verification, file access controls, or input validation to make tests pass.
- Do not add test-only backdoors to production endpoints.

## Git / scope discipline

Unless explicitly requested:
- do not commit;
- do not push;
- do not open/merge PRs;
- do not deploy;
- do not modify unrelated files.

Before finishing:

```bash
git status --short
git diff --check
git diff
```

Review the final diff for accidental scope expansion.

## Definition of done

A task is done when:

1. The requested behavior is implemented, not merely patched visually.
2. Relevant regression tests exist.
3. Relevant targeted tests pass.
4. Full backend/frontend checks pass when the change warrants them.
5. Integration/E2E checks are run when the risk crosses process, persistence, concurrency, or service boundaries.
6. No secrets, debug artifacts, temporary fixtures, or unrelated changes remain.
7. The final report states:
   - what changed;
   - what was tested;
   - what was not tested and why;
   - any migration/rollout step still required.

## Keeping this file healthy

Keep root `AGENTS.md` concise. Do not add task-specific plans or long architecture essays here.

If one area develops stable specialized rules, add a smaller `AGENTS.md` (or `AGENTS.override.md` when replacement semantics are desired) in that directory instead of growing this file indefinitely.

Update this guide only for recurring repository-wide mistakes or durable conventions.
