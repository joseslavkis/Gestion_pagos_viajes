-- Imputación manual administrativa: origen explícito + motivo + método opcional.
-- Aditiva, preserva historial, segura para rerun.

BEGIN;

-- Origen explícito del submission.
ALTER TABLE payment_submissions
    ADD COLUMN IF NOT EXISTS source VARCHAR(32);

UPDATE payment_submissions
SET source = 'CUSTOMER_SUBMISSION'
WHERE source IS NULL;

ALTER TABLE payment_submissions
    ALTER COLUMN source SET DEFAULT 'CUSTOMER_SUBMISSION';

ALTER TABLE payment_submissions
    ALTER COLUMN source SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'payment_submissions'::regclass
          AND conname = 'ck_payment_submissions_source'
    ) THEN
        ALTER TABLE payment_submissions
            ADD CONSTRAINT ck_payment_submissions_source
            CHECK (source IN ('CUSTOMER_SUBMISSION', 'ADMIN_MANUAL'));
    END IF;
END
$$;

-- Motivo libre opcional de la imputación manual (500 chars, consistente con admin_observation).
ALTER TABLE payment_submissions
    ADD COLUMN IF NOT EXISTS manual_reason VARCHAR(500);

-- Un manual nunca pasa por PENDING: lo acredita directo como RESOLVED.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'payment_submissions'::regclass
          AND conname = 'ck_payment_submissions_manual_not_pending'
    ) THEN
        ALTER TABLE payment_submissions
            ADD CONSTRAINT ck_payment_submissions_manual_not_pending
            CHECK (source <> 'ADMIN_MANUAL' OR status <> 'PENDING');
    END IF;
END
$$;

-- El método deja de ser obligatorio a nivel DB: el manual no requiere método
-- (efectivo / corrección / canal externo). El flujo cliente lo sigue exigiendo
-- en el service. El CHECK existente permite NULL (NULL → UNKNOWN → pasa).
ALTER TABLE payment_submissions
    ALTER COLUMN payment_method DROP NOT NULL;

-- Pero el método sigue siendo obligatorio para customer submissions: solo el
-- manual puede no informar método. Los históricos tienen método (la columna
-- era NOT NULL), así que el CHECK validado es seguro.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'payment_submissions'::regclass
          AND conname = 'ck_payment_submissions_customer_requires_method'
    ) THEN
        ALTER TABLE payment_submissions
            ADD CONSTRAINT ck_payment_submissions_customer_requires_method
            CHECK (source = 'ADMIN_MANUAL' OR payment_method IS NOT NULL);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = current_schema()
          AND tablename = 'payment_submissions'
          AND indexname = 'idx_payment_submissions_source'
    ) THEN
        CREATE INDEX idx_payment_submissions_source
            ON payment_submissions (source);
    END IF;
END
$$;

COMMIT;
