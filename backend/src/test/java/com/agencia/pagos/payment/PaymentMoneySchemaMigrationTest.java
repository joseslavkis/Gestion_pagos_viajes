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
        Files.writeString(docker, "#!/bin/sh\nprintf '%s\\n' \"$MOCK_SCHEMA_STATE\"\n");
        assertThat(docker.toFile().setExecutable(true)).isTrue();
        assertThat(runPreflight(docker, "NOT_READY")).isEqualTo(1);
        assertThat(runPreflight(docker, "READY")).isZero();
        String script = Files.readString(PREFLIGHT);
        assertThat(script).contains("payment_money_schema_readiness.sql")
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
                .contains("SPRING_PROFILES_ACTIVE: \"${SPRING_PROFILES_ACTIVE:-local}\"")
                .contains("SPRING_JPA_HIBERNATE_DDL_AUTO: \"${SPRING_JPA_HIBERNATE_DDL_AUTO:-update}\"");
    }

    @Test
    void olderBackendMappingsValidateAgainstWidenedSchema() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("SET search_path TO public");
            sql.execute(Files.readString(MIGRATION));
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

    private int runPreflight(Path docker, String state) throws Exception {
        ProcessBuilder process = new ProcessBuilder("bash", PREFLIGHT.toAbsolutePath().toString())
                .directory(Path.of(".").toAbsolutePath().toFile())
                .redirectErrorStream(true);
        process.environment().put("PATH", temporaryDirectory + ":" + process.environment().get("PATH"));
        process.environment().put("MOCK_SCHEMA_STATE", state);
        Process child = process.start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = child.waitFor();
        assertThat(output).contains(state.equals("READY")
                ? "Payment schema preflight passed" : "Payment schema is incompatible");
        return exit;
    }
}
