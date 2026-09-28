package com.agencia.pagos.payment;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentSubmissionAttachmentsSqlTest {

    @Test
    void migrationBackfillsOnlyMissingLegacyKeyWithoutMovingSurvivors() throws Exception {
        String migration = Files.readString(Path.of("sql/20260928_payment_submission_attachments.sql"));
        assertTrue(migration.contains("UNIQUE (submission_id, position)"));
        assertTrue(migration.contains("position >= 0 AND position < 5"));
        assertTrue(migration.contains("a.submission_id = p.id AND a.file_key = p.file_key"));
        assertTrue(migration.contains("ON CONFLICT (submission_id, position) DO NOTHING"));
        assertFalse(migration.contains("UPDATE payment_submission_attachments"));
    }

    @Test
    void readinessAcceptsSparseValidPositionsAndDetectsMissingLegacyReference() throws Exception {
        String readiness = Files.readString(Path.of("sql/payment_submission_attachments_readiness.sql"));
        assertTrue(readiness.contains("a.submission_id = p.id AND a.file_key = p.file_key"));
        assertTrue(readiness.contains("COUNT(*) > 5 OR MIN(position) < 0 OR MAX(position) >= 5"));
        assertFalse(readiness.contains("MAX(position) <> COUNT(*) - 1"));
    }
}
