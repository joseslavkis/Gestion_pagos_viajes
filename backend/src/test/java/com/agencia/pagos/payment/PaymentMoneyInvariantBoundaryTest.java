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
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "app.mail.to=test@agencia.com",
        "app.mail.from=no-reply@agencia.com",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("production")
@Import(TestcontainersConfiguration.class)
/*
 * create-drop seeds only the disposable Testcontainers database; a separate context
 * verifies the migrated schema with the production profile and Hibernate validation.
 */
class PaymentMoneyInvariantBoundaryTest {

    private static final Path MIGRATION = Path.of("sql", "20260917_payment_money_invariants.sql");
    private static final Path DEPLOY_NOTE =
            Path.of("sql", "20260917_payment_money_invariants_deploy_note.md");
    private static final Path FULL_STACK_SCRIPT =
            Path.of("..", "scripts", "test-payment-full-stack.sh");
    private static final Path AGGREGATE_SCRIPT =
            Path.of("..", "scripts", "test-payment-money-invariants.sh");
    private static final Path CONCURRENCY_SCRIPT =
            Path.of("..", "scripts", "test-payment-concurrency.sh");
    private static final Path SCHEMA_PREFLIGHT_SCRIPT =
            Path.of("..", "scripts", "check-payment-schema-readiness.sh");
    private static final Path READINESS_QUERY =
            Path.of("sql", "payment_money_schema_readiness.sql");
    private static final Path CI_WORKFLOW =
            Path.of("..", ".github", "workflows", "ci-cd.yml");
    private static final Path COMPOSE_FILE = Path.of("..", "docker-compose.yml");
    private static final Path PRODUCTION_PROPERTIES = Path.of("src", "main", "resources", "application-production.properties");
    private static final Path OPERATIONS_GUIDE =
            Path.of("..", "docs", "payment-money-invariants.md");

    @TempDir
    private Path temporaryDirectory;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Test
    void productionProfile_hasNoDeterministicFxSeam() throws Exception {
        Map<String, ExchangeRateQuoteProvider> providers =
                applicationContext.getBeansOfType(ExchangeRateQuoteProvider.class);

        assertThat(providers).containsOnlyKeys("exchangeRateService");
        assertThat(providers.get("exchangeRateService")).isExactlyInstanceOf(ExchangeRateService.class);
        assertThat(providers).doesNotContainKey("deterministicExchangeRateQuoteProvider");
        assertThat(environment.matchesProfiles("payment-fx-test")).isFalse();
        assertThat(environment.getProperty("exchange-rate.current-url")).isNull();
        assertThat(environment.getProperty("exchange-rate.historical-url")).isNull();
        assertThat(environment.getProperty("exchange-rate.test-provider-enabled")).isNull();
        assertThat(environment.getProperty("payment.fx-test.call-log")).isNull();
        assertThat(Files.readString(PRODUCTION_PROPERTIES))
                .contains("spring.jpa.hibernate.ddl-auto=validate");
    }

    @Test
    void migration_isIdempotent_preservesRows_andSchemaValidates() throws Exception {
        assertThat(MIGRATION)
                .as("the dated payment money migration must exist before migration verification can pass")
                .exists();
        String migrationSql = Files.readString(MIGRATION);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA payment_money_migration_contract");
            statement.execute("SET search_path TO payment_money_migration_contract");
            statement.execute("""
                    CREATE TABLE payment_submissions (
                        id BIGINT PRIMARY KEY,
                        exchange_rate NUMERIC(10,2),
                        exchange_rate_source VARCHAR(255),
                        status VARCHAR(16) NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO payment_submissions (id, exchange_rate, exchange_rate_source, status)
                    VALUES
                        (1, 1234.56, 'legacy-source', 'RESOLVED'),
                        (2, NULL, NULL, 'PENDING')
                    """);

            statement.execute(migrationSql);
            statement.execute("""
                    INSERT INTO payment_submissions (id, exchange_rate, exchange_rate_source, status)
                    VALUES (4, NULL, NULL, 'PENDING')
                    """);
            SQLException explicitNullFailure = null;
            try {
                statement.execute("""
                        INSERT INTO payment_submissions (
                            id, exchange_rate, exchange_rate_source, status, calculation_version
                        )
                        VALUES (5, NULL, NULL, 'PENDING', NULL)
                        """);
            } catch (SQLException exception) {
                explicitNullFailure = exception;
            }
            assertNotNull(explicitNullFailure,
                    "an older writer may omit calculation_version, but explicit NULL must be rejected");
            assertThat(explicitNullFailure.getSQLState()).isEqualTo("23502");

            statement.execute("""
                    INSERT INTO payment_submissions (
                        id, exchange_rate, exchange_rate_source, status,
                        exchange_rate_scale, exchange_rate_provider, calculation_version
                    )
                    VALUES (
                        3, 1234.56789012, 'snapshot-source', 'PENDING',
                        8, 'snapshot-provider', '2'
                    )
                    """);
            List<SnapshotRow> afterFirstRun = readSnapshotRows(statement);
            statement.execute(migrationSql);
            List<SnapshotRow> afterSecondRun = readSnapshotRows(statement);

            assertThat(afterSecondRun).containsExactlyElementsOf(afterFirstRun);
            assertThat(afterFirstRun).containsExactly(
                    new SnapshotRow(1L, new BigDecimal("1234.56000000"), 2, "legacy-source", "v1", "RESOLVED"),
                    new SnapshotRow(2L, null, null, null, "v1", "PENDING"),
                    new SnapshotRow(3L, new BigDecimal("1234.56789012"), 8, "snapshot-provider", "2", "PENDING"),
                    new SnapshotRow(4L, null, null, null, "v1", "PENDING")
            );

            try (ResultSet columns = statement.executeQuery("""
                    SELECT numeric_precision, numeric_scale, is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = 'payment_money_migration_contract'
                      AND table_name = 'payment_submissions'
                      AND column_name = 'exchange_rate'
                    """)) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getInt("numeric_precision")).isEqualTo(18);
                assertThat(columns.getInt("numeric_scale")).isEqualTo(8);
                assertThat(columns.getString("is_nullable")).isEqualTo("YES");
            }
            try (ResultSet versionColumn = statement.executeQuery("""
                    SELECT column_default, is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = 'payment_money_migration_contract'
                      AND table_name = 'payment_submissions'
                      AND column_name = 'calculation_version'
                    """)) {
                assertThat(versionColumn.next()).isTrue();
                assertThat(versionColumn.getString("column_default")).contains("v1");
                assertThat(versionColumn.getString("is_nullable")).isEqualTo("NO");
            }
        }
    }

    @Test
    void deploymentChecksReadOnlySchemaReadinessBeforeStartingProductionBackend() throws Exception {
        assertThat(SCHEMA_PREFLIGHT_SCRIPT).exists().isRegularFile();
        assertThat(PRODUCTION_PROPERTIES).exists().isRegularFile();

        String preflight = Files.readString(SCHEMA_PREFLIGHT_SCRIPT);
        assertThat(preflight)
                .contains("docker compose --project-directory")
                .contains("exec -T db")
                .contains("psql -v ON_ERROR_STOP=1")
                .contains("payment_money_schema_readiness.sql")
                .doesNotContain("20260917_payment_money_invariants.sql")
                .doesNotContain("UPDATE payment_submissions")
                .doesNotContain("column_default LIKE '%v1%'");

        String readinessQuery = Files.readString(READINESS_QUERY);
        assertThat(readinessQuery)
                .contains("information_schema.columns")
                .contains("column_default = quote_literal('v1')")
                .contains("convalidated")
                .contains("pg_get_constraintdef")
                .contains("calculation_version = '2'")
                .contains("exchange_rate_requested_date")
                .contains("exchange_rate_effective_date")
                .doesNotContain("UPDATE ");

        String workflow = Files.readString(CI_WORKFLOW);
        int shaVerification = workflow.indexOf("test \"$ACTUAL_SHA\" = \"$DEPLOY_SHA\"");
        int schemaPreflight = workflow.indexOf("./scripts/check-payment-schema-readiness.sh");
        int backendStart = workflow.indexOf("docker compose up -d --build backend");
        assertThat(shaVerification).isGreaterThanOrEqualTo(0);
        assertThat(schemaPreflight).isGreaterThan(shaVerification);
        assertThat(backendStart).isGreaterThan(schemaPreflight);
        assertThat(workflow)
                .contains("SPRING_PROFILES_ACTIVE=production")
                .contains("SPRING_JPA_HIBERNATE_DDL_AUTO=validate")
                .doesNotContain("20260917_payment_money_invariants.sql");

        assertThat(Files.readString(COMPOSE_FILE))
                .contains("SPRING_PROFILES_ACTIVE: \"${SPRING_PROFILES_ACTIVE:-local}\"")
                .contains("SPRING_JPA_HIBERNATE_DDL_AUTO: \"${SPRING_JPA_HIBERNATE_DDL_AUTO:-update}\"");
        assertThat(Files.readString(PRODUCTION_PROPERTIES))
                .contains("spring.jpa.hibernate.ddl-auto=validate");
    }

    @Test
    void schemaPreflightDoesNotAcceptBroadVersionDefaultMatches() throws Exception {
        String preflight = Files.readString(SCHEMA_PREFLIGHT_SCRIPT);

        assertThat(preflight).doesNotContain("column_default LIKE '%v1%'");
    }

    @Test
    void sharedReadinessQueryRejectsIncorrectDefaultConstraintAndVersionTwoFxMetadata() throws Exception {
        assertThat(READINESS_QUERY).exists().isRegularFile();
        String readinessSql = Files.readString(READINESS_QUERY);
        String migrationSql = Files.readString(MIGRATION);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS payment_money_readiness_contract CASCADE");
            statement.execute("CREATE SCHEMA payment_money_readiness_contract");
            statement.execute("SET search_path TO payment_money_readiness_contract");
            statement.execute("CREATE TABLE trips (id BIGINT PRIMARY KEY, currency VARCHAR(3) NOT NULL)");
            statement.execute("INSERT INTO trips (id, currency) VALUES (1, 'ARS')");
            statement.execute("""
                    CREATE TABLE payment_submissions (
                        id BIGINT PRIMARY KEY,
                        trip_id BIGINT NOT NULL REFERENCES trips(id),
                        payment_currency VARCHAR(3) NOT NULL,
                        exchange_rate NUMERIC(10,2),
                        exchange_rate_source VARCHAR(64),
                        exchange_rate_requested_date DATE,
                        exchange_rate_effective_date DATE,
                        status VARCHAR(16) NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO payment_submissions (
                        id, trip_id, payment_currency, exchange_rate, exchange_rate_source, status
                    ) VALUES (1, 1, 'ARS', NULL, NULL, 'PENDING')
                    """);
            statement.execute(migrationSql);
            statement.execute("""
                    INSERT INTO payment_submissions (
                        id, trip_id, payment_currency, exchange_rate, exchange_rate_scale,
                        exchange_rate_provider, exchange_rate_source, exchange_rate_requested_date,
                        exchange_rate_effective_date, status, calculation_version
                    ) VALUES (
                        2, 1, 'USD', 1234.56, 2, 'provider-a', 'official',
                        DATE '2026-09-23', DATE '2026-09-23', 'PENDING', '2'
                    )
                    """);

            assertThat(readinessState(statement, readinessSql)).isEqualTo("READY");

            statement.execute("ALTER TABLE payment_submissions ALTER COLUMN calculation_version SET DEFAULT 'v10'");
            assertThat(readinessState(statement, readinessSql)).isEqualTo("NOT_READY");
            statement.execute("ALTER TABLE payment_submissions ALTER COLUMN calculation_version SET DEFAULT 'v1'");
            assertThat(readinessState(statement, readinessSql)).isEqualTo("READY");

            statement.execute("ALTER TABLE payment_submissions DROP CONSTRAINT ck_payment_submissions_exchange_rate_scale");
            statement.execute("""
                    ALTER TABLE payment_submissions
                    ADD CONSTRAINT ck_payment_submissions_exchange_rate_scale
                    CHECK (exchange_rate_scale IS NULL OR exchange_rate_scale <= 8)
                    """);
            assertThat(readinessState(statement, readinessSql)).isEqualTo("NOT_READY");

            statement.execute("ALTER TABLE payment_submissions DROP CONSTRAINT ck_payment_submissions_exchange_rate_scale");
            statement.execute("""
                    ALTER TABLE payment_submissions
                    ADD CONSTRAINT ck_payment_submissions_exchange_rate_scale
                    CHECK (exchange_rate_scale IS NULL OR exchange_rate_scale BETWEEN 0 AND 8)
                    NOT VALID
                    """);
            assertThat(readinessState(statement, readinessSql)).isEqualTo("NOT_READY");
            statement.execute("ALTER TABLE payment_submissions VALIDATE CONSTRAINT ck_payment_submissions_exchange_rate_scale");
            assertThat(readinessState(statement, readinessSql)).isEqualTo("READY");

            statement.execute("UPDATE payment_submissions SET exchange_rate_provider = NULL WHERE id = 2");
            assertThat(readinessState(statement, readinessSql)).isEqualTo("NOT_READY");
            statement.execute("UPDATE payment_submissions SET exchange_rate_provider = 'provider-a' WHERE id = 2");
            assertThat(readinessState(statement, readinessSql)).isEqualTo("READY");
        }
    }

    @Test
    void schemaPreflight_failsClosedForIncompatibleSchema() throws Exception {
        Path dockerBin = temporaryDirectory.resolve("bin");
        Files.createDirectories(dockerBin);
        Path mockDocker = dockerBin.resolve("docker");
        Files.writeString(mockDocker, "#!/bin/sh\nprintf '%s\\n' \"$MOCK_SCHEMA_STATE\"\n");
        assertThat(mockDocker.toFile().setExecutable(true)).isTrue();

        PreflightResult notReady = runSchemaPreflight(dockerBin, "NOT_READY");
        assertThat(notReady.exitCode()).isEqualTo(1);
        assertThat(notReady.output())
                .contains("Payment schema is incompatible")
                .contains("backend deployment was not started");

        PreflightResult ready = runSchemaPreflight(dockerBin, "READY");
        assertThat(ready.exitCode()).isZero();
        assertThat(ready.output()).contains("Payment schema preflight passed.");
    }

    @Test
    void migratedSchema_startsWithHibernateValidateWithoutMutation() throws Exception {
        String migrationSql = Files.readString(MIGRATION);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE payment_submissions DROP CONSTRAINT IF EXISTS ck_payment_submissions_exchange_rate_scale");
            statement.execute("ALTER TABLE payment_submissions DROP COLUMN IF EXISTS exchange_rate_scale");
            statement.execute("ALTER TABLE payment_submissions DROP COLUMN IF EXISTS exchange_rate_provider");
            statement.execute("ALTER TABLE payment_submissions DROP COLUMN IF EXISTS calculation_version");
            statement.execute("""
                    ALTER TABLE payment_submissions
                    ALTER COLUMN exchange_rate TYPE NUMERIC(10,2)
                    USING exchange_rate::NUMERIC(10,2)
                    """);
            statement.execute(migrationSql);
        }

        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        try (ConfigurableApplicationContext validatingContext = new SpringApplicationBuilder(PagosApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--spring.profiles.active=production",
                        "--spring.datasource.url=" + hikari.getJdbcUrl(),
                        "--spring.datasource.username=" + hikari.getUsername(),
                        "--spring.datasource.password=" + hikari.getPassword(),
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.jpa.open-in-view=false",
                        "--jwt.access.secret=dGVzdC1zZWNyZXQtcGFyYS1jaS1vbmx5LXF1ZS1zZWEtbG8tc3VmaWNpZW50ZW1lbnRlLWxhcmdvLXBhcmEtaG1hYw==",
                        "--app.frontend.url=http://localhost:30003",
                        "--app.notifications.installments.enabled=false",
                        "--app.storage.receipts.cleanup.enabled=false")) {
            assertThat(validatingContext.isActive()).isTrue();
        }
    }

    @Test
    void deployNote_documentsSafeMaintenanceMigrationAndApplicationRollout() throws Exception {
        assertThat(DEPLOY_NOTE).exists();
        String deployNote = Files.readString(DEPLOY_NOTE);

        assertThat(deployNote)
                .contains("Freeze payment registration, review, and void writes")
                .contains("verify it by restoring it to a disposable database")
                .contains("docker compose exec -T db")
                .contains("read-only checks")
                .contains("explicit `production` profile")
                .contains("Vercel production promotion")
                .contains("Verify the deployed SHA and backend health")
                .contains("every earlier token without the required `cv=2` claim is invalid immediately")
                .contains("read-only verification queries")
                .contains("corrective `UPDATE`")
                .contains("exact `DEFAULT 'v1'`")
                .contains("validated scale-range constraint definition")
                .contains("calculation-version `2` cross-currency submissions")
                .contains("do not shrink or drop snapshot columns")
                .contains("suspend cross-currency payments");
    }

    @Test
    void deliveryArtifacts_preserveIsolationOps001AndDocumentUserBehavior() throws Exception {
        assertThat(FULL_STACK_SCRIPT).exists().isRegularFile();
        assertThat(AGGREGATE_SCRIPT).exists().isRegularFile();
        assertThat(OPERATIONS_GUIDE).exists().isRegularFile();

        String fullStack = Files.readString(FULL_STACK_SCRIPT);
        assertThat(fullStack)
                .contains("127.0.0.1")
                .contains("payment-fx-test")
                .contains("PAYMENT_E2E_FX_CALL_LOG")
                .contains("docker rm -f")
                .doesNotContain("$ROOT_DIR/.env");

        assertThat(Files.readString(AGGREGATE_SCRIPT))
                .contains("test-payment-full-stack.sh")
                .contains("test-payment-concurrency.sh")
                .contains("./mvnw test")
                .contains("npm run lint");
        assertThat(Files.readString(CONCURRENCY_SCRIPT))
                .contains("crossCurrencyConcurrentReview_conservesAmountsAndOnlyApprovesOnce")
                .contains("crossCurrencyConcurrentVoid_reversesPersistedAllocationAndOnlyVoidsOnce");

        String workflow = Files.readString(CI_WORKFLOW);
        assertThat(workflow)
                .contains("pull_request:\n    branches: [main, dev]")
                .contains("push:\n    branches: [main, dev]")
                .contains("github.event_name == 'push' && github.ref == 'refs/heads/main'")
                .contains("git reset --hard \"$DEPLOY_SHA\"")
                .contains("npm run lint")
                .contains("./scripts/check-payment-schema-readiness.sh")
                .contains("SPRING_PROFILES_ACTIVE=production")
                .contains("SPRING_JPA_HIBERNATE_DDL_AUTO=validate")
                .contains("Payment Money Invariants");

        assertThat(Files.readString(OPERATIONS_GUIDE))
                .contains("write freeze + verified backup → SQL migration")
                .contains("Do not recalculate approved payment history")
                .contains("No credit or overpayment ledger")
                .contains("Manual input is preserved")
                .contains("CASE F")
                .contains("CASE G")
                .contains("CASE J")
                .contains("anchorRemainingAmount");
    }

    private static List<SnapshotRow> readSnapshotRows(Statement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery("""
                SELECT id, exchange_rate, exchange_rate_scale, exchange_rate_provider,
                       calculation_version, status
                FROM payment_submissions
                ORDER BY id
                """)) {
            java.util.ArrayList<SnapshotRow> result = new java.util.ArrayList<>();
            while (rows.next()) {
                result.add(new SnapshotRow(
                        rows.getLong("id"),
                        rows.getBigDecimal("exchange_rate"),
                        (Integer) rows.getObject("exchange_rate_scale"),
                        rows.getString("exchange_rate_provider"),
                        rows.getString("calculation_version"),
                        rows.getString("status")
                ));
            }
            return result;
        }
    }

    private static String readinessState(Statement statement, String readinessSql) throws Exception {
        try (ResultSet result = statement.executeQuery(readinessSql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private PreflightResult runSchemaPreflight(Path dockerBin, String schemaState) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                SCHEMA_PREFLIGHT_SCRIPT.toAbsolutePath().toString()
        ).directory(Path.of(".").toAbsolutePath().toFile()).redirectErrorStream(true);
        Map<String, String> processEnvironment = processBuilder.environment();
        processEnvironment.put(
                "PATH",
                dockerBin + System.getProperty("path.separator") + processEnvironment.getOrDefault("PATH", "")
        );
        processEnvironment.put("MOCK_SCHEMA_STATE", schemaState);

        Process process = processBuilder.start();
        String output;
        try (var outputStream = process.getInputStream()) {
            output = new String(outputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new PreflightResult(process.waitFor(), output);
    }

    private record SnapshotRow(
            Long id,
            BigDecimal exchangeRate,
            Integer exchangeRateScale,
            String provider,
            String calculationVersion,
            String status
    ) {
    }

    private record PreflightResult(int exitCode, String output) {
    }
}
