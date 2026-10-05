package com.agencia.pagos.payment;

import com.agencia.pagos.PagosApplication;
import com.agencia.pagos.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.notifications.installments.enabled=false",
        "app.storage.receipts.cleanup.enabled=false",
        "app.mail.to=test@agencia.com",
        "app.mail.from=no-reply@agencia.com"
})
@Import(TestcontainersConfiguration.class)
class PaymentMoneySchemaMigrationTest {
    private static final Path MIGRATION = Path.of("sql/20260917_payment_money_invariants.sql");
    private static final Path READINESS = Path.of("sql/payment_money_schema_readiness.sql");
    private static final Path AUDIT = Path.of("sql/payment_money_financial_audit.sql");
    private static final Path PREREQUISITE = Path.of("sql/20260608_add_exchange_rate_audit.sql");
    private static final Path PREFLIGHT = Path.of("../scripts/check-payment-schema-readiness.sh");
    private static final Path ATTACHMENT_MIGRATION = Path.of("sql/20260928_payment_submission_attachments.sql");
    private static final Path ATTACHMENT_READINESS = Path.of("sql/payment_submission_attachments_readiness.sql");
    private static final Path MANUAL_MIGRATION = Path.of("sql/20261002_manual_imputation.sql");
    private static final Path MANUAL_READINESS = Path.of("sql/manual_imputation_schema_readiness.sql");
    private static final Path ADMIN_REVIEW_MIGRATION = Path.of("sql/20261005_admin_review_currency.sql");
    private static final Path ADMIN_REVIEW_READINESS = Path.of("sql/admin_review_currency_schema_readiness.sql");
    private static final List<String> OUTCOME_SNAPSHOT_COLUMNS = List.of(
            "currency", "exchange_rate", "exchange_rate_scale", "exchange_rate_requested_date",
            "exchange_rate_effective_date", "exchange_rate_source", "exchange_rate_provider",
            "exchange_rate_provider_timestamp", "calculation_version");
    private static final Path WORKFLOW = Path.of("../.github/workflows/ci-cd.yml");

    @TempDir
    private Path temporaryDirectory;

    @Autowired
    private DataSource dataSource;

    @Test
    void legacyBackfillDoesNotRepairCorruptVersionTwoSnapshotsOnRerun() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA migration_backfill_test");
            sql.execute("SET search_path TO migration_backfill_test");
            createLegacyTables(sql);
            sql.execute(Files.readString(PREREQUISITE));
            sql.execute("""
                    INSERT INTO payment_submissions (id, trip_id, payment_currency, exchange_rate,
                        exchange_rate_source, reported_amount, amount_in_trip_currency, status)
                    VALUES (1, 1, 'ARS', 1200.12, 'legacy-quote', 12.00, 0.01, 'PENDING'),
                           (2, 2, 'ARS', NULL, NULL, 10.00, 10.00, 'PENDING')
                    """);
            sql.execute(Files.readString(MIGRATION));
            assertThat(snapshot(sql, 1)).isEqualTo("v1|2|legacy-quote|1200.12000000");
            assertThat(snapshot(sql, 2)).isEqualTo("v1|null|null|null");
            assertThat(readiness(sql)).isEqualTo("READY");

            sql.execute("""
                    INSERT INTO payment_submissions (id, trip_id, payment_currency, exchange_rate,
                        exchange_rate_source, reported_amount, amount_in_trip_currency, status,
                        calculation_version, exchange_rate_scale, exchange_rate_provider)
                    VALUES (3, 1, 'ARS', 1200.12345678, 'v2-quote', 12.00, 0.01, 'PENDING', '2', NULL, NULL)
                    """);
            assertThat(readiness(sql)).isEqualTo("NOT_READY");
            String beforeRerun = snapshot(sql, 3);
            sql.execute(Files.readString(MIGRATION));
            assertThat(snapshot(sql, 3)).isEqualTo(beforeRerun).isEqualTo("2|null|null|1200.12345678");
            assertThat(readiness(sql)).isEqualTo("NOT_READY");
            assertThat(snapshot(sql, 1)).isEqualTo("v1|2|legacy-quote|1200.12000000");

            sql.execute("""
                    UPDATE payment_submissions SET exchange_rate_scale = 8,
                        exchange_rate_provider = 'v2-quote',
                        exchange_rate_requested_date = DATE '2026-09-01',
                        exchange_rate_effective_date = DATE '2026-09-01'
                    WHERE id = 3
                    """);
            assertThat(readiness(sql)).isEqualTo("READY");
            sql.execute("ALTER TABLE payment_submissions ALTER COLUMN calculation_version SET DEFAULT 'v10'");
            assertThat(readiness(sql)).isEqualTo("NOT_READY");
            sql.execute("ALTER TABLE payment_submissions ALTER COLUMN calculation_version SET DEFAULT 'v1'");
            assertThat(readiness(sql)).isEqualTo("READY");
            sql.execute("ALTER TABLE payment_submissions DROP CONSTRAINT ck_payment_submissions_exchange_rate_scale");
            sql.execute("""
                    ALTER TABLE payment_submissions ADD CONSTRAINT ck_payment_submissions_exchange_rate_scale
                    CHECK (exchange_rate_scale IS NULL OR exchange_rate_scale BETWEEN 0 AND 8) NOT VALID
                    """);
            assertThat(readiness(sql)).isEqualTo("NOT_READY");
            sql.execute("ALTER TABLE payment_submissions VALIDATE CONSTRAINT ck_payment_submissions_exchange_rate_scale");
            assertThat(readiness(sql)).isEqualTo("READY");
            String beforeWeakenedConstraint = snapshot(sql, 3);
            sql.execute("ALTER TABLE payment_submissions DROP CONSTRAINT ck_payment_submissions_exchange_rate_scale");
            sql.execute("""
                    ALTER TABLE payment_submissions ADD CONSTRAINT ck_payment_submissions_exchange_rate_scale
                    CHECK (exchange_rate_scale IS NULL OR exchange_rate_scale <= 8)
                    """);
            assertThat(readiness(sql)).isEqualTo("NOT_READY");
            assertThat(snapshot(sql, 3)).isEqualTo(beforeWeakenedConstraint);
            sql.execute("ALTER TABLE payment_submissions DROP CONSTRAINT ck_payment_submissions_exchange_rate_scale");
            sql.execute("""
                    ALTER TABLE payment_submissions ADD CONSTRAINT ck_payment_submissions_exchange_rate_scale
                    CHECK (exchange_rate_scale IS NULL OR exchange_rate_scale BETWEEN 0 AND 8)
                    """);
            assertThat(readiness(sql)).isEqualTo("READY");
            assertThat(snapshot(sql, 3)).isEqualTo(beforeWeakenedConstraint);
            sql.execute("UPDATE payment_submissions SET exchange_rate = 0 WHERE id = 3");
            assertThat(readiness(sql)).isEqualTo("NOT_READY");
            sql.execute("UPDATE payment_submissions SET exchange_rate = 1200.12345678 WHERE id = 3");
            assertThat(readiness(sql)).isEqualTo("READY");

            sql.execute("INSERT INTO payment_submissions (id, trip_id, payment_currency, reported_amount, amount_in_trip_currency, status) VALUES (4, 2, 'ARS', 10, 10, 'PENDING')");
            assertThat(snapshot(sql, 4)).startsWith("v1|");
            sql.execute("BEGIN");
            sql.execute("SAVEPOINT explicit_null_test");
            assertThatThrownBy(() -> sql.execute("""
                    INSERT INTO payment_submissions (id, trip_id, payment_currency, reported_amount,
                        amount_in_trip_currency, status, calculation_version)
                    VALUES (5, 2, 'ARS', 10, 10, 'PENDING', NULL)
                    """)).isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23502");
            sql.execute("ROLLBACK TO SAVEPOINT explicit_null_test");
            sql.execute("COMMIT");
        }
    }

    @Test
    void prerequisiteMustExistBeforeMigration() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA migration_prerequisite_test");
            sql.execute("SET search_path TO migration_prerequisite_test");
            sql.execute("CREATE TABLE payment_submissions (id BIGINT PRIMARY KEY, exchange_rate NUMERIC(10,2))");
            assertThatThrownBy(() -> sql.execute(Files.readString(MIGRATION)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("20260608_add_exchange_rate_audit.sql");
            sql.execute("ROLLBACK");
            try (ResultSet columns = sql.executeQuery("""
                    SELECT numeric_precision, numeric_scale FROM information_schema.columns
                    WHERE table_schema = 'migration_prerequisite_test'
                      AND table_name = 'payment_submissions' AND column_name = 'exchange_rate'
                    """)) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt(1)).isEqualTo(10);
                assertThat(columns.getInt(2)).isEqualTo(2);
            }
        }
    }

    @Test
    void financialAuditReportsSevenFindingsWithoutMislabelingLegacyReceiptBalances() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA migration_audit_test");
            sql.execute("SET search_path TO migration_audit_test");
            createLegacyTables(sql);
            sql.execute(Files.readString(PREREQUISITE));
            sql.execute(Files.readString(MIGRATION));
            sql.execute("""
                    INSERT INTO payment_submissions (id, trip_id, payment_currency, reported_amount,
                        amount_in_trip_currency, exchange_rate, status, calculation_version,
                        exchange_rate_source, exchange_rate_scale, exchange_rate_provider,
                        exchange_rate_requested_date, exchange_rate_effective_date) VALUES
                    (1, 1, 'ARS', 1200, 1, 1200, 'RESOLVED', 'v1', 'legacy', 2, 'legacy', NULL, NULL),
                    (2, 1, 'ARS', 1200, 0.99, 1200, 'PENDING', 'v1', 'legacy', 2, 'legacy', NULL, NULL),
                    (3, 1, 'ARS', 1200, 1, 1200, 'PENDING', '2', 'snapshot', NULL, NULL, DATE '2026-09-01', DATE '2026-09-01'),
                    (4, 2, 'ARS', 10, 10, 1, 'PENDING', '2', NULL, 0, NULL, NULL, NULL),
                    (5, 1, 'ARS', 1200, 1, 1200.12345678, 'PENDING', 'v1', 'legacy', 2, 'legacy', NULL, NULL),
                    (7, 2, 'ARS', 10, 10, NULL, 'RESOLVED', 'v1', NULL, NULL, NULL, NULL, NULL)
                    """);
            sql.execute("ALTER TABLE payment_submissions ALTER COLUMN calculation_version DROP NOT NULL");
            sql.execute("""
                    INSERT INTO payment_submissions (id, trip_id, payment_currency, reported_amount,
                        amount_in_trip_currency, status, calculation_version)
                    VALUES (6, 2, 'ARS', 10, 10, 'PENDING', NULL)
                    """);
            sql.execute("""
                    INSERT INTO payment_outcomes VALUES
                    (10, 1, 'APPROVED', 100, 1), (11, 7, 'APPROVED', 10, 10)
                    """);
            sql.execute("""
                    INSERT INTO installments VALUES (30, 50.00)
                    """);
            sql.execute("""
                    INSERT INTO payment_receipts VALUES (40, 30, 40.00, 'APPROVED')
                    """);
            sql.execute("""
                    INSERT INTO payment_allocations (id, outcome_id, reported_amount, amount_in_trip_currency,
                        installment_id) VALUES
                    (20, 10, 99, 0.99, NULL), (21, 11, 10, 10, 30)
                    """);
            List<String> findings = auditFindings(sql);
            assertThat(findings).containsExactlyInAnyOrder(
                    "approved_trip_allocation_mismatch:1", "approved_reported_allocation_mismatch:1",
                    "pending_legacy_snapshot_discrepancy:2", "incomplete_v2_cross_currency_snapshot:3",
                    "null_calculation_version:6", "invalid_exchange_rate_scale:5",
                    "same_currency_invented_rate:4");
            assertThat(Files.readString(AUDIT).toUpperCase())
                    .doesNotContain("UPDATE ", "DELETE ", "INSERT ", "ALTER ");
        }
    }

    @Test
    void preflightFailsClosedWithoutChangingTheDatabase() throws Exception {
        Path docker = temporaryDirectory.resolve("docker");
        Files.writeString(docker, """
                #!/bin/sh
                query="$(cat)"
                case "$query" in
                  *ck_payment_outcomes_currency*) name=admin_review; state="$MOCK_ADMIN_REVIEW_STATE" ;;
                  *to_regclass*) name=attachments; state="$MOCK_ATTACHMENT_STATE" ;;
                  *manual_reason*) name=manual; state="$MOCK_MANUAL_STATE" ;;
                  *) name=money; state="$MOCK_MONEY_STATE" ;;
                esac
                printf '%s\\n' "$name" >> "$MOCK_QUERY_LOG"
                [ "$state" != ERROR ] || exit 12
                printf '%s\\n' "$state"
                """);
        assertThat(docker.toFile().setExecutable(true)).isTrue();
        assertThat(runPreflight("READY", "NOT_READY", "READY")).isEqualTo(1);
        assertThat(runPreflight("NOT_READY", "READY", "READY")).isEqualTo(1);
        assertThat(runPreflight("READY", "READY", "NOT_READY")).isEqualTo(1);
        assertThat(runPreflight("READY", "ERROR", "READY")).isEqualTo(1);
        assertThat(runPreflight("READY", "READY", "ERROR")).isEqualTo(1);
        assertThat(runPreflight("ERROR", "READY", "READY")).isEqualTo(1);
        assertThat(runPreflight("READY", "READY\nREADY", "READY")).isEqualTo(1);
        assertThat(runPreflight("READY", "READY", "READY")).isZero();
        assertThat(runPreflight("READY", "READY", "READY", "NOT_READY")).isEqualTo(1);
        assertThat(runPreflight("READY", "READY", "READY", "ERROR")).isEqualTo(1);
        assertThat(runPreflight("READY", "READY", "READY", "READY\nREADY")).isEqualTo(1);
        String script = Files.readString(PREFLIGHT);
        assertThat(script).contains("payment_money_schema_readiness.sql")
                .contains("payment_submission_attachments_readiness.sql")
                .contains("manual_imputation_schema_readiness.sql")
                .contains("admin_review_currency_schema_readiness.sql")
                .contains("psql -v ON_ERROR_STOP=1")
                .doesNotContain("20260917_payment_money_invariants.sql");

        String workflow = Files.readString(WORKFLOW);
        assertThat(workflow.indexOf("./scripts/check-payment-schema-readiness.sh"))
                .isGreaterThan(workflow.indexOf("test \"$ACTUAL_SHA\" = \"$DEPLOY_SHA\""));
        assertThat(workflow.indexOf("docker compose up -d --build backend"))
                .isGreaterThan(workflow.indexOf("./scripts/check-payment-schema-readiness.sh"));
        assertThat(workflow).contains("SPRING_PROFILES_ACTIVE=production SPRING_JPA_HIBERNATE_DDL_AUTO=validate");
        assertThat(Files.readString(Path.of("src/main/resources/application-production.properties")))
                .contains("spring.jpa.hibernate.ddl-auto=validate");
        assertThat(Files.readString(Path.of("../docker-compose.yml")))
                .contains("SPRING_PROFILES_ACTIVE: \"${SPRING_PROFILES_ACTIVE:-production}\"")
                .contains("SPRING_JPA_HIBERNATE_DDL_AUTO: \"${SPRING_JPA_HIBERNATE_DDL_AUTO:-validate}\"");
    }

    @Test
    void attachmentMigrationBackfillsOnceAndReadinessRejectsCorruption() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA migration_attachments_test");
            sql.execute("SET search_path TO migration_attachments_test");
            sql.execute("CREATE TABLE payment_submissions (id BIGINT PRIMARY KEY, file_key TEXT)");
            sql.execute("INSERT INTO payment_submissions VALUES (1, 'existing.png'), (2, ''), (3, NULL)");
            assertThatThrownBy(() -> attachmentReadiness(sql)).isInstanceOf(SQLException.class);
            sql.execute(Files.readString(ATTACHMENT_MIGRATION));
            assertThat(sql.executeQuery("SELECT 1 FROM payment_submission_attachments WHERE submission_id = 1 AND position = 0 AND file_key = 'existing.png'").next()).isTrue();
            assertThat(attachmentReadiness(sql)).isEqualTo("READY");
            sql.execute("ALTER TABLE payment_submission_attachments DROP CONSTRAINT payment_submission_attachments_submission_id_fkey");
            sql.execute("ALTER TABLE payment_submission_attachments ADD CONSTRAINT payment_submission_attachments_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES payment_submissions(id)");
            assertThat(attachmentReadiness(sql)).isEqualTo("NOT_READY");
            sql.execute("ALTER TABLE payment_submission_attachments DROP CONSTRAINT payment_submission_attachments_submission_id_fkey");
            sql.execute("ALTER TABLE payment_submission_attachments ADD CONSTRAINT payment_submission_attachments_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES payment_submissions(id) ON DELETE CASCADE NOT VALID");
            assertThat(attachmentReadiness(sql)).isEqualTo("NOT_READY");
            sql.execute("ALTER TABLE payment_submission_attachments VALIDATE CONSTRAINT payment_submission_attachments_submission_id_fkey");
            assertThat(attachmentReadiness(sql)).isEqualTo("READY");
            sql.execute(Files.readString(ATTACHMENT_MIGRATION));
            try (ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM payment_submission_attachments WHERE submission_id = 1")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
            // Only stored keys are copied; migration SQL has no physical-file access.
            sql.execute("DELETE FROM payment_submission_attachments WHERE submission_id = 1");
            assertThat(attachmentReadiness(sql)).isEqualTo("NOT_READY");
            sql.execute(Files.readString(ATTACHMENT_MIGRATION));
            sql.execute("UPDATE payment_submission_attachments SET file_key = 'wrong.png' WHERE submission_id = 1");
            assertThat(attachmentReadiness(sql)).isEqualTo("NOT_READY");
            sql.execute("UPDATE payment_submission_attachments SET file_key = 'existing.png', position = 4 WHERE submission_id = 1");
            assertThat(attachmentReadiness(sql)).isEqualTo("READY");
            sql.execute("ALTER TABLE payment_submission_attachments DROP CONSTRAINT payment_submission_attachments_position_check");
            sql.execute("UPDATE payment_submission_attachments SET position = 5 WHERE submission_id = 1");
            assertThat(attachmentReadiness(sql)).isEqualTo("NOT_READY");
            sql.execute("ALTER TABLE payment_submission_attachments DROP CONSTRAINT uq_payment_submission_attachment_position");
            sql.execute("INSERT INTO payment_submission_attachments (submission_id, position, file_key) SELECT 1, n, 'extra-' || n FROM generate_series(0, 5) n");
            assertThat(attachmentReadiness(sql)).isEqualTo("NOT_READY");
        }
    }

    @Test
    void manualMigrationBackfillsSourceAndEnforcesInvariants() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA migration_manual_test");
            sql.execute("SET search_path TO migration_manual_test");
            sql.execute("""
                    CREATE TABLE payment_submissions (
                        id BIGINT PRIMARY KEY, trip_id BIGINT NOT NULL,
                        payment_currency VARCHAR(3) NOT NULL,
                        reported_amount NUMERIC(10,2) NOT NULL,
                        amount_in_trip_currency NUMERIC(10,2) NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        payment_method VARCHAR(20) NOT NULL)
                    """);
            sql.execute("""
                    INSERT INTO payment_submissions
                        (id, trip_id, payment_currency, reported_amount,
                         amount_in_trip_currency, status, payment_method)
                    VALUES (1, 1, 'ARS', 10, 10, 'PENDING', 'BANK_TRANSFER')
                    """);
            sql.execute(Files.readString(MANUAL_MIGRATION));

            // Backfill: históricos → CUSTOMER_SUBMISSION, source NOT NULL.
            try (ResultSet rows = sql.executeQuery(
                    "SELECT source FROM payment_submissions WHERE id = 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("CUSTOMER_SUBMISSION");
            }
            assertThat(manualReadiness(sql)).isEqualTo("READY");

            // source inválido y manual PENDING se rechazan a nivel DB.
            sql.execute("BEGIN");
            sql.execute("SAVEPOINT manual_invariants");
            assertThatThrownBy(() -> sql.execute("""
                    INSERT INTO payment_submissions
                        (id, trip_id, payment_currency, reported_amount,
                         amount_in_trip_currency, status, payment_method, source)
                    VALUES (2, 1, 'ARS', 10, 10, 'PENDING', 'BANK_TRANSFER', 'WRONG')
                    """)).isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23514");
            sql.execute("ROLLBACK TO SAVEPOINT manual_invariants");
            assertThatThrownBy(() -> sql.execute("""
                    INSERT INTO payment_submissions
                        (id, trip_id, payment_currency, reported_amount,
                         amount_in_trip_currency, status, payment_method, source)
                    VALUES (3, 1, 'ARS', 10, 10, 'PENDING', NULL, 'ADMIN_MANUAL')
                    """)).isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23514");
            sql.execute("ROLLBACK TO SAVEPOINT manual_invariants");
            // Customer sin método se rechaza; manual sin método es válido.
            assertThatThrownBy(() -> sql.execute("""
                    INSERT INTO payment_submissions
                        (id, trip_id, payment_currency, reported_amount,
                         amount_in_trip_currency, status, payment_method, source)
                    VALUES (4, 1, 'ARS', 10, 10, 'PENDING', NULL, 'CUSTOMER_SUBMISSION')
                    """)).isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23514");
            sql.execute("ROLLBACK TO SAVEPOINT manual_invariants");
            sql.execute("""
                    INSERT INTO payment_submissions
                        (id, trip_id, payment_currency, reported_amount,
                         amount_in_trip_currency, status, payment_method, source, manual_reason)
                    VALUES (5, 1, 'ARS', 10, 10, 'RESOLVED', NULL, 'ADMIN_MANUAL', 'Efectivo')
                    """);
            sql.execute("COMMIT");
            assertThat(manualReadiness(sql)).isEqualTo("READY");

            // El readiness detecta la falta del nuevo CHECK y la migración es re-ejecutable.
            sql.execute("ALTER TABLE payment_submissions DROP CONSTRAINT ck_payment_submissions_customer_requires_method");
            assertThat(manualReadiness(sql)).isEqualTo("NOT_READY");
            sql.execute(Files.readString(MANUAL_MIGRATION));
            assertThat(manualReadiness(sql)).isEqualTo("READY");
        }
    }

    @Test
    void migratedSchemaValidatesWithProductionBackendMappings() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("SET search_path TO public");
            sql.execute(Files.readString(MIGRATION));
            sql.execute(Files.readString(ADMIN_REVIEW_MIGRATION));
            assertThat(adminReviewReadiness(sql)).isEqualTo("READY");
        }
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PagosApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + hikari.getJdbcUrl(),
                        "--spring.datasource.username=" + hikari.getUsername(),
                        "--spring.datasource.password=" + hikari.getPassword(),
                        "--spring.profiles.active=production",
                        "--jwt.access.secret=dGVzdC1zZWNyZXQtcGFyYS1jaS1vbmx5LXF1ZS1zZWEtbG8tc3VmaWNpZW50ZW1lbnRlLWxhcmdvLXBhcmEtaG1hYw==",
                        "--app.frontend.url=http://localhost:30003",
                        "--app.notifications.installments.enabled=false",
                        "--app.storage.receipts.cleanup.enabled=false")) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
                    .isEqualTo("validate");
        }
    }

    @Test
    void outcomeBackfillPreservesAllHistoryAndIndependentSnapshotsOnRerun() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            createOutcomeHistory(sql, "outcome_backfill_test");
            String history = outcomeHistory(sql);
            sql.execute(Files.readString(ADMIN_REVIEW_MIGRATION));
            assertThat(outcomeHistory(sql)).isEqualTo(history);
            assertThat(adminReviewReadiness(sql)).isEqualTo("READY");
            try (ResultSet rows = sql.executeQuery("""
                    SELECT count(*) FROM payment_outcomes o JOIN payment_submissions s ON s.id = o.submission_id
                    WHERE o.currency = s.payment_currency
                      AND o.exchange_rate IS NOT DISTINCT FROM s.exchange_rate
                      AND o.exchange_rate_scale IS NOT DISTINCT FROM s.exchange_rate_scale
                      AND o.exchange_rate_requested_date IS NOT DISTINCT FROM s.exchange_rate_requested_date
                      AND o.exchange_rate_effective_date IS NOT DISTINCT FROM s.exchange_rate_effective_date
                      AND o.exchange_rate_source IS NOT DISTINCT FROM s.exchange_rate_source
                      AND o.exchange_rate_provider IS NOT DISTINCT FROM s.exchange_rate_provider
                      AND o.exchange_rate_provider_timestamp IS NOT DISTINCT FROM s.exchange_rate_provider_timestamp
                      AND o.calculation_version IS NOT DISTINCT FROM s.calculation_version
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
            sql.execute(Files.readString(ADMIN_REVIEW_MIGRATION));
            assertThat(outcomeHistory(sql)).isEqualTo(history);
            sql.execute("""
                    INSERT INTO payment_outcomes (id, submission_id, status, reported_amount,
                        amount_in_trip_currency, currency, exchange_rate, exchange_rate_scale,
                        exchange_rate_requested_date, exchange_rate_effective_date, exchange_rate_source,
                        exchange_rate_provider, exchange_rate_provider_timestamp, calculation_version)
                    VALUES (5, 1, 'APPROVED', 20.00, 20.00, 'USD', 999.12345678, 8,
                        DATE '2026-08-20', DATE '2026-08-19', 'admin-quote', 'admin-provider', 'admin-time', '2'),
                        (6, 1, 'REJECTED', 0.01, 0.01, 'USD', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                    """);
            String independent = queryText(sql, "SELECT jsonb_agg(to_jsonb(o) ORDER BY id)::text FROM payment_outcomes o");
            sql.execute(Files.readString(ADMIN_REVIEW_MIGRATION));
            assertThat(queryText(sql, "SELECT jsonb_agg(to_jsonb(o) ORDER BY id)::text FROM payment_outcomes o"))
                    .isEqualTo(independent);
            assertThat(adminReviewReadiness(sql)).isEqualTo("READY");
        }
    }

    @Test
    void outcomeReadinessRejectsEveryMissingColumnAndIncorrectStorageContract() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            createOutcomeHistory(sql, "outcome_columns_test");
            sql.execute(Files.readString(ADMIN_REVIEW_MIGRATION));
            List<String> mutations = new ArrayList<>();
            for (String column : OUTCOME_SNAPSHOT_COLUMNS) {
                mutations.add("ALTER TABLE payment_outcomes DROP COLUMN " + column + " CASCADE");
            }
            mutations.addAll(List.of(
                    "ALTER TABLE payment_outcomes ALTER COLUMN currency DROP NOT NULL",
                    "ALTER TABLE payment_outcomes ALTER COLUMN currency TYPE VARCHAR(4)",
                    "ALTER TABLE payment_outcomes ALTER COLUMN currency SET DEFAULT 'ARS'",
                    "ALTER TABLE payment_outcomes ALTER COLUMN exchange_rate TYPE NUMERIC(19,8)",
                    "ALTER TABLE payment_outcomes ALTER COLUMN exchange_rate TYPE NUMERIC(18,7)",
                    "ALTER TABLE payment_outcomes ALTER COLUMN exchange_rate_scale TYPE BIGINT",
                    "ALTER TABLE payment_outcomes ALTER COLUMN exchange_rate_requested_date TYPE TIMESTAMP",
                    "ALTER TABLE payment_outcomes ALTER COLUMN exchange_rate_effective_date TYPE TIMESTAMP",
                    "ALTER TABLE payment_outcomes ALTER COLUMN exchange_rate_source TYPE VARCHAR(65)",
                    "ALTER TABLE payment_outcomes ALTER COLUMN exchange_rate_provider TYPE VARCHAR(65)",
                    "ALTER TABLE payment_outcomes ALTER COLUMN exchange_rate_provider_timestamp TYPE VARCHAR(129)",
                    "ALTER TABLE payment_outcomes ALTER COLUMN calculation_version TYPE VARCHAR(17)",
                    "ALTER TABLE payment_outcomes ALTER COLUMN reported_amount TYPE NUMERIC(11,2)",
                    "ALTER TABLE payment_outcomes ALTER COLUMN amount_in_trip_currency TYPE NUMERIC(10,3)"));
            sql.execute("BEGIN");
            for (String mutation : mutations) {
                sql.execute("SAVEPOINT schema_mutation");
                sql.execute(mutation);
                assertThat(adminReviewReadiness(sql)).as(mutation).isEqualTo("NOT_READY");
                sql.execute("ROLLBACK TO SAVEPOINT schema_mutation");
                assertThat(adminReviewReadiness(sql)).isEqualTo("READY");
            }
            // Nullable metadata is part of the contract, not an optional catalog detail.
            for (String column : OUTCOME_SNAPSHOT_COLUMNS.subList(1, OUTCOME_SNAPSHOT_COLUMNS.size())) {
                sql.execute("SAVEPOINT schema_mutation");
                sql.execute("DELETE FROM payment_allocations");
                sql.execute("DELETE FROM payment_outcomes");
                sql.execute("ALTER TABLE payment_outcomes ALTER COLUMN " + column + " SET NOT NULL");
                assertThat(adminReviewReadiness(sql)).as(column).isEqualTo("NOT_READY");
                sql.execute("ROLLBACK TO SAVEPOINT schema_mutation");
            }
            sql.execute("ALTER TABLE payment_outcomes ALTER COLUMN currency DROP NOT NULL");
            sql.execute("UPDATE payment_outcomes SET currency = NULL WHERE id = 1");
            assertThat(adminReviewReadiness(sql)).isEqualTo("NOT_READY");
            sql.execute("ROLLBACK");
        }
    }

    @Test
    void outcomeConstraintsRejectInvalidWritesAndReadinessChecksActualValidatedRules() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            createOutcomeHistory(sql, "outcome_constraints_test");
            sql.execute(Files.readString(ADMIN_REVIEW_MIGRATION));
            sql.execute("BEGIN");
            assertOutcomeWriteRejected(sql, "UPDATE payment_outcomes SET currency = NULL WHERE id = 1", "23502");
            assertOutcomeWriteRejected(sql, "UPDATE payment_outcomes SET currency = 'EUR' WHERE id = 1", "23514");
            assertOutcomeWriteRejected(sql, "UPDATE payment_outcomes SET currency = 'USDX' WHERE id = 1", "22001");
            assertOutcomeWriteRejected(sql, "UPDATE payment_outcomes SET exchange_rate = 10000000000 WHERE id = 1", "22003");
            assertOutcomeWriteRejected(sql, "UPDATE payment_outcomes SET reported_amount = 100000000 WHERE id = 1", "22003");
            assertOutcomeWriteRejected(sql, "UPDATE payment_outcomes SET amount_in_trip_currency = 100000000 WHERE id = 1", "22003");
            assertOutcomeWriteRejected(sql, "UPDATE payment_outcomes SET exchange_rate_scale = -1 WHERE id = 1", "23514");
            assertOutcomeWriteRejected(sql, "UPDATE payment_outcomes SET exchange_rate_scale = 9 WHERE id = 1", "23514");
            assertOutcomeWriteRejected(sql, """
                    INSERT INTO payment_outcomes (id, submission_id, status, reported_amount, amount_in_trip_currency)
                    VALUES (5, 1, 'APPROVED', 1, 1)
                    """, "23502"); // Old writers cannot satisfy the required snapshot.
            for (String scale : List.of("0", "8", "NULL")) {
                sql.execute("UPDATE payment_outcomes SET exchange_rate_scale = " + scale + " WHERE id = 1");
            }
            sql.execute("UPDATE payment_outcomes SET exchange_rate = 1234.123456789 WHERE id = 1");
            assertThat(queryText(sql, "SELECT exchange_rate::text FROM payment_outcomes WHERE id = 1"))
                    .isEqualTo("1234.12345679"); // PostgreSQL rounds at its declared scale; it does not reject extra digits.
            for (String rule : List.of("currency", "exchange_rate_scale")) {
                String constraint = "ck_payment_outcomes_" + rule;
                String correct = rule.equals("currency") ? "currency IN ('ARS', 'USD')"
                        : "exchange_rate_scale IS NULL OR exchange_rate_scale BETWEEN 0 AND 8";
                String wrong = rule.equals("currency") ? "currency IN ('ARS', 'USD', 'EUR')"
                        : "exchange_rate_scale IS NULL OR exchange_rate_scale <= 8";
                sql.execute("SAVEPOINT constraint_mutation");
                sql.execute("ALTER TABLE payment_outcomes DROP CONSTRAINT " + constraint);
                assertThat(adminReviewReadiness(sql)).isEqualTo("NOT_READY");
                sql.execute("ALTER TABLE payment_outcomes ADD CONSTRAINT " + constraint + " CHECK (" + wrong + ")");
                assertThat(adminReviewReadiness(sql)).isEqualTo("NOT_READY");
                sql.execute("ALTER TABLE payment_outcomes DROP CONSTRAINT " + constraint);
                sql.execute("ALTER TABLE payment_outcomes ADD CONSTRAINT " + constraint + " CHECK (" + correct + ") NOT VALID");
                assertThat(adminReviewReadiness(sql)).isEqualTo("NOT_READY");
                sql.execute("ALTER TABLE payment_outcomes VALIDATE CONSTRAINT " + constraint);
                assertThat(adminReviewReadiness(sql)).isEqualTo("READY");
                sql.execute("ROLLBACK TO SAVEPOINT constraint_mutation");
            }
            // Literal case is significant. An empty table must not make a
            // lowercase-only currency rule look compatible with ARS/USD writers.
            sql.execute("DELETE FROM payment_allocations");
            sql.execute("DELETE FROM payment_outcomes");
            sql.execute("ALTER TABLE payment_outcomes DROP CONSTRAINT ck_payment_outcomes_currency");
            sql.execute("ALTER TABLE payment_outcomes ADD CONSTRAINT ck_payment_outcomes_currency CHECK (currency IN ('ars', 'usd'))");
            assertThat(adminReviewReadiness(sql)).isEqualTo("NOT_READY");
            sql.execute("ROLLBACK");
        }
    }

    @Test
    void outcomeMigrationRollsBackInvalidHistoryAndRequiresOriginalSnapshotColumns() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            createOutcomeHistory(sql, "outcome_rollback_test");
            sql.execute("UPDATE payment_submissions SET payment_currency = 'EUR' WHERE id = 1");
            String before = outcomeHistory(sql);
            assertThatThrownBy(() -> sql.execute(Files.readString(ADMIN_REVIEW_MIGRATION)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_payment_outcomes_currency");
            sql.execute("ROLLBACK");
            assertThat(outcomeHistory(sql)).isEqualTo(before);
            assertThat(queryText(sql, """
                    SELECT count(*)::text FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = 'payment_outcomes' AND column_name = 'currency'
                    """)).isEqualTo("0");
            sql.execute("UPDATE payment_submissions SET payment_currency = 'ARS' WHERE id = 1");
            sql.execute("ALTER TABLE payment_submissions DROP COLUMN exchange_rate_provider");
            assertThatThrownBy(() -> sql.execute(Files.readString(ADMIN_REVIEW_MIGRATION)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Missing submission snapshot prerequisites");
            sql.execute("ROLLBACK");
        }
    }

    private static void createOutcomeHistory(Statement sql, String schema) throws Exception {
        sql.execute("CREATE SCHEMA " + schema);
        sql.execute("SET search_path TO " + schema);
        createLegacyTables(sql);
        sql.execute(Files.readString(PREREQUISITE));
        sql.execute(Files.readString(MIGRATION));
        sql.execute("ALTER TABLE payment_submissions ADD COLUMN payment_method VARCHAR(20) NOT NULL DEFAULT 'CASH'");
        sql.execute(Files.readString(MANUAL_MIGRATION));
        sql.execute("""
                INSERT INTO payment_submissions (id, trip_id, payment_currency, exchange_rate, reported_amount,
                    amount_in_trip_currency, status, exchange_rate_scale, exchange_rate_requested_date,
                    exchange_rate_effective_date, exchange_rate_source, exchange_rate_provider,
                    exchange_rate_provider_timestamp, calculation_version, source, payment_method, manual_reason)
                VALUES (1, 1, 'ARS', 1200.12345678, 12000, 10, 'VOIDED', 8, DATE '2026-09-01',
                    DATE '2026-08-31', 'original-source', 'original-provider', 'original-time', '2', 'CUSTOMER_SUBMISSION', 'CASH', NULL),
                    (2, 1, 'USD', NULL, 30, 30, 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL, 'v1', 'CUSTOMER_SUBMISSION', 'CASH', NULL),
                    (3, 2, 'ARS', NULL, 15, 15, 'RESOLVED', NULL, NULL, NULL, NULL, NULL, NULL, 'v1', 'ADMIN_MANUAL', NULL, 'Historical cash');
                INSERT INTO payment_outcomes VALUES (1, 1, 'VOIDED', 6000, 5), (2, 1, 'REJECTED', 6000, 5),
                    (3, 2, 'APPROVED', 25, 25), (4, 3, 'APPROVED', 15, 15);
                INSERT INTO installments VALUES (1, 40);
                INSERT INTO payment_allocations VALUES (1, 1, 6000, 5, 1), (2, 3, 25, 25, 1), (3, 4, 15, 15, 1);
                """);
    }

    private static String outcomeHistory(Statement sql) throws SQLException {
        String excluded = "ARRAY['" + String.join("','", OUTCOME_SNAPSHOT_COLUMNS) + "']";
        return queryText(sql, "SELECT jsonb_agg(to_jsonb(o) - " + excluded + " ORDER BY id)::text FROM payment_outcomes o")
                + queryText(sql, "SELECT jsonb_agg(to_jsonb(s) ORDER BY id)::text FROM payment_submissions s")
                + queryText(sql, "SELECT jsonb_agg(to_jsonb(a) ORDER BY id)::text FROM payment_allocations a")
                + queryText(sql, "SELECT jsonb_agg(to_jsonb(i) ORDER BY id)::text FROM installments i");
    }

    private static String queryText(Statement sql, String query) throws SQLException {
        try (ResultSet result = sql.executeQuery(query)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static String adminReviewReadiness(Statement sql) throws Exception {
        return queryText(sql, Files.readString(ADMIN_REVIEW_READINESS));
    }

    private static void assertOutcomeWriteRejected(Statement sql, String mutation, String state) throws SQLException {
        sql.execute("SAVEPOINT invalid_outcome");
        assertThatThrownBy(() -> sql.execute(mutation)).isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo(state);
        sql.execute("ROLLBACK TO SAVEPOINT invalid_outcome");
    }

    private static void createLegacyTables(Statement sql) throws SQLException {
        sql.execute("CREATE TABLE trips (id BIGINT PRIMARY KEY, currency VARCHAR(3) NOT NULL)");
        sql.execute("INSERT INTO trips VALUES (1, 'USD'), (2, 'ARS')");
        sql.execute("""
                CREATE TABLE payment_submissions (
                    id BIGINT PRIMARY KEY, trip_id BIGINT NOT NULL REFERENCES trips(id),
                    payment_currency VARCHAR(3) NOT NULL, exchange_rate NUMERIC(10,2),
                    reported_amount NUMERIC(10,2) NOT NULL,
                    amount_in_trip_currency NUMERIC(10,2) NOT NULL, status VARCHAR(16) NOT NULL)
                """);
        sql.execute("""
                CREATE TABLE payment_outcomes (
                    id BIGINT PRIMARY KEY, submission_id BIGINT NOT NULL REFERENCES payment_submissions(id),
                    status VARCHAR(16) NOT NULL, reported_amount NUMERIC(10,2) NOT NULL,
                    amount_in_trip_currency NUMERIC(10,2) NOT NULL)
                """);
        sql.execute("""
                CREATE TABLE installments (id BIGINT PRIMARY KEY, paid_amount NUMERIC(10,2) NOT NULL)
                """);
        sql.execute("""
                CREATE TABLE payment_receipts (id BIGINT PRIMARY KEY,
                    installment_id BIGINT NOT NULL REFERENCES installments(id),
                    reported_amount NUMERIC(10,2) NOT NULL, status VARCHAR(16) NOT NULL)
                """);
        sql.execute("""
                CREATE TABLE payment_allocations (
                    id BIGINT PRIMARY KEY, outcome_id BIGINT NOT NULL REFERENCES payment_outcomes(id),
                    reported_amount NUMERIC(10,2) NOT NULL, amount_in_trip_currency NUMERIC(10,2) NOT NULL,
                    installment_id BIGINT REFERENCES installments(id))
                """);
    }

    private static String snapshot(Statement sql, int id) throws SQLException {
        try (ResultSet result = sql.executeQuery("""
                SELECT calculation_version, exchange_rate_scale, exchange_rate_provider, exchange_rate
                FROM payment_submissions WHERE id = %d
                """.formatted(id))) {
            assertThat(result.next()).isTrue();
            BigDecimal rate = result.getBigDecimal(4);
            return result.getString(1) + "|" + result.getString(2) + "|" + result.getString(3)
                    + "|" + (rate == null ? "null" : rate.toPlainString());
        }
    }

    private static String readiness(Statement sql) throws Exception {
        try (ResultSet result = sql.executeQuery(Files.readString(READINESS))) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static List<String> auditFindings(Statement sql) throws Exception {
        List<String> findings = new ArrayList<>();
        try (ResultSet result = sql.executeQuery(Files.readString(AUDIT))) {
            while (result.next()) {
                findings.add(result.getString("audit_type") + ":" + result.getLong("submission_id"));
            }
        }
        return findings;
    }

    private int runPreflight(String money, String attachments, String manual) throws Exception {
        return runPreflight(money, attachments, manual, "READY");
    }

    private int runPreflight(String money, String attachments, String manual, String adminReview) throws Exception {
        Path log = temporaryDirectory.resolve("queries.log");
        Files.deleteIfExists(log);
        ProcessBuilder process = new ProcessBuilder("bash", PREFLIGHT.toAbsolutePath().toString())
                .directory(Path.of(".").toAbsolutePath().toFile())
                .redirectErrorStream(true);
        process.environment().put("PATH", temporaryDirectory + ":" + process.environment().get("PATH"));
        process.environment().put("MOCK_MONEY_STATE", money);
        process.environment().put("MOCK_ATTACHMENT_STATE", attachments);
        process.environment().put("MOCK_MANUAL_STATE", manual);
        process.environment().put("MOCK_ADMIN_REVIEW_STATE", adminReview);
        process.environment().put("MOCK_QUERY_LOG", log.toString());
        Process child = process.start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = child.waitFor();
        assertThat(output).contains(money.equals("READY") && attachments.equals("READY") && manual.equals("READY") && adminReview.equals("READY")
                ? "Payment schema preflight passed" : "Payment schema is incompatible");
        List<String> expectedLog = money.equals("READY")
                ? (attachments.equals("READY")
                    ? (manual.equals("READY") ? List.of("money", "attachments", "manual", "admin_review") : List.of("money", "attachments", "manual"))
                    : List.of("money", "attachments"))
                : List.of("money");
        assertThat(Files.readAllLines(log)).containsExactlyElementsOf(expectedLog);
        return exit;
    }

    private static String attachmentReadiness(Statement sql) throws Exception {
        try (ResultSet result = sql.executeQuery(Files.readString(ATTACHMENT_READINESS))) {
            assertThat(result.next()).isTrue();
            String state = result.getString(1);
            assertThat(result.next()).isFalse();
            return state;
        }
    }

    private static String manualReadiness(Statement sql) throws Exception {
        try (ResultSet result = sql.executeQuery(Files.readString(MANUAL_READINESS))) {
            assertThat(result.next()).isTrue();
            String state = result.getString(1);
            assertThat(result.next()).isFalse();
            return state;
        }
    }
}
