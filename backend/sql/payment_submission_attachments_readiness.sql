-- Both counts must be zero after the migration, before the backend is deployed.
SELECT COUNT(*) AS missing_legacy_attachments
FROM payment_submissions p
WHERE p.file_key <> '' AND NOT EXISTS (
    SELECT 1 FROM payment_submission_attachments a
    WHERE a.submission_id = p.id AND a.file_key = p.file_key
);

SELECT COUNT(*) AS invalid_attachment_groups FROM (
    SELECT submission_id FROM payment_submission_attachments
    GROUP BY submission_id HAVING COUNT(*) > 5 OR MIN(position) < 0 OR MAX(position) >= 5
) invalid_groups;
