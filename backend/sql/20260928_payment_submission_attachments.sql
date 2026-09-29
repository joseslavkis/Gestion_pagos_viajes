-- Apply before deploying the backend with Hibernate schema validation enabled.
CREATE TABLE IF NOT EXISTS payment_submission_attachments (
    id BIGSERIAL PRIMARY KEY,
    submission_id BIGINT NOT NULL REFERENCES payment_submissions(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 0 AND position < 5),
    file_key TEXT NOT NULL,
    CONSTRAINT uq_payment_submission_attachment_position UNIQUE (submission_id, position)
);

CREATE INDEX IF NOT EXISTS idx_payment_submission_attachments_submission
    ON payment_submission_attachments (submission_id);

-- Safe to rerun; never replace an existing child row or modify legacy file_key.
INSERT INTO payment_submission_attachments (submission_id, position, file_key)
SELECT p.id, 0, p.file_key FROM payment_submissions p
WHERE p.file_key IS NOT NULL AND p.file_key <> ''
  AND NOT EXISTS (
      SELECT 1 FROM payment_submission_attachments a
      WHERE a.submission_id = p.id AND a.file_key = p.file_key
  )
ON CONFLICT (submission_id, position) DO NOTHING;
