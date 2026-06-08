# Exchange rate audit migration — manual deployment steps

Production uses `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`. Hibernate will NOT
create the new columns automatically. The backend will fail to start until
the additive migration is applied.

## Required order

1. **Back up the production database** before applying any change.
2. **Run the migration** in `backend/sql/20260608_add_exchange_rate_audit.sql`
   on the production database. Statements are additive and idempotent; existing
   rows keep their original NULL audit values.
3. **Verify the four columns exist** on `payment_submissions`:
   - `exchange_rate_requested_date` (DATE)
   - `exchange_rate_effective_date` (DATE)
   - `exchange_rate_source` (VARCHAR(64))
   - `exchange_rate_provider_timestamp` (VARCHAR(128))
4. **Deploy the backend** image. Hibernate will validate against the new columns
   on startup.
5. **Deploy the frontend** in coordination with the backend so the
   `previewToken` field is in the new request bodies.
6. **Run payment smoke tests**:
   - USD trip paid in USD (same currency, no quote).
   - USD trip paid in ARS with today's date (current quote).
   - USD trip paid in ARS with a historical business date (ArgentinaDatos).
   - USD trip paid in ARS with a Saturday (fallback to previous Friday).
   - Cross-currency submit without a preview token must be rejected with 400.
   - Cross-currency submit with a valid preview token must succeed without
     invoking the external provider while holding the row lock.

## Rollback

The migration is additive. To roll back, drop the four columns and redeploy
the previous backend image. No data is lost because all four columns are
nullable.
