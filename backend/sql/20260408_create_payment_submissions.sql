CREATE TABLE IF NOT EXISTS payment_submissions (
    id BIGSERIAL PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    student_id BIGINT,
    anchor_installment_id BIGINT NOT NULL,
    bank_account_id BIGINT,
    reported_amount NUMERIC(10, 2) NOT NULL,
    payment_currency VARCHAR(3) NOT NULL CHECK (payment_currency IN ('ARS', 'USD')),
    exchange_rate NUMERIC(10, 2),
    amount_in_trip_currency NUMERIC(10, 2) NOT NULL,
    reported_payment_date DATE NOT NULL,
    payment_method VARCHAR(20) NOT NULL CHECK (payment_method IN ('BANK_TRANSFER', 'CASH', 'DEPOSIT', 'OTHER')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'RESOLVED', 'VOIDED')),
    file_key TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payment_submissions_trip
        FOREIGN KEY (trip_id)
        REFERENCES trips (id),
    CONSTRAINT fk_payment_submissions_user
        FOREIGN KEY (user_id)
        REFERENCES users (id),
    CONSTRAINT fk_payment_submissions_student
        FOREIGN KEY (student_id)
        REFERENCES students (id),
    CONSTRAINT fk_payment_submissions_anchor_installment
        FOREIGN KEY (anchor_installment_id)
        REFERENCES installments (id),
    CONSTRAINT fk_payment_submissions_bank_account
        FOREIGN KEY (bank_account_id)
        REFERENCES bank_accounts (id)
);

CREATE INDEX IF NOT EXISTS idx_payment_submissions_status
    ON payment_submissions (status);

CREATE INDEX IF NOT EXISTS idx_payment_submissions_scope
    ON payment_submissions (trip_id, user_id, student_id);

CREATE INDEX IF NOT EXISTS idx_payment_submissions_user
    ON payment_submissions (user_id);

CREATE INDEX IF NOT EXISTS idx_payment_submissions_reported_date
    ON payment_submissions (reported_payment_date);

CREATE TABLE IF NOT EXISTS payment_outcomes (
    id BIGSERIAL PRIMARY KEY,
    submission_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('APPROVED', 'REJECTED', 'VOIDED')),
    reported_amount NUMERIC(10, 2) NOT NULL,
    amount_in_trip_currency NUMERIC(10, 2) NOT NULL,
    admin_observation VARCHAR(500),
    resolved_by_email VARCHAR(255),
    resolved_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payment_outcomes_submission
        FOREIGN KEY (submission_id)
        REFERENCES payment_submissions (id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_payment_outcomes_submission
    ON payment_outcomes (submission_id);

CREATE INDEX IF NOT EXISTS idx_payment_outcomes_status
    ON payment_outcomes (status);

CREATE TABLE IF NOT EXISTS payment_allocations (
    id BIGSERIAL PRIMARY KEY,
    outcome_id BIGINT NOT NULL,
    installment_id BIGINT NOT NULL,
    allocation_order INTEGER NOT NULL,
    reported_amount NUMERIC(10, 2) NOT NULL,
    amount_in_trip_currency NUMERIC(10, 2) NOT NULL,
    CONSTRAINT fk_payment_allocations_outcome
        FOREIGN KEY (outcome_id)
        REFERENCES payment_outcomes (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_payment_allocations_installment
        FOREIGN KEY (installment_id)
        REFERENCES installments (id)
);

CREATE INDEX IF NOT EXISTS idx_payment_allocations_outcome
    ON payment_allocations (outcome_id);

CREATE INDEX IF NOT EXISTS idx_payment_allocations_installment
    ON payment_allocations (installment_id);
