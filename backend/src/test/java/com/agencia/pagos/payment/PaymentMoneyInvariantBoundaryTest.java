package com.agencia.pagos.payment;

import com.agencia.pagos.PagosApplication;
import com.agencia.pagos.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.mail.to=test@agencia.com",
        "app.mail.from=no-reply@agencia.com"
})
@ActiveProfiles("production")
@Import(TestcontainersConfiguration.class)
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
    private static final Path CI_WORKFLOW =
            Path.of("..", ".github", "workflows", "ci-cd.yml");
    private static final Path OPERATIONS_GUIDE =
            Path.of("..", "docs", "payment-money-invariants.md");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Test
    void productionProfile_hasNoDeterministicFxSeam() {
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
                    new SnapshotRow(3L, new BigDecimal("1234.56789012"), 8, "snapshot-provider", "2", "PENDING")
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
        }
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
    void deployNote_documentsOrderingVerificationRetirementAndNonDestructiveRollback() throws Exception {
        assertThat(DEPLOY_NOTE).exists();
        String deployNote = Files.readString(DEPLOY_NOTE);

        assertThat(deployNote)
                .contains("SQL")
                .contains("backend")
                .contains("frontend")
                .contains("verify the exact image SHA/version")
                .contains("Retire the previous calculation path")
                .contains("Do not shrink or drop snapshot columns")
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
                .contains("Payment Money Invariants");

        assertThat(Files.readString(OPERATIONS_GUIDE))
                .contains("SQL → backend → frontend")
                .contains("Do not recalculate approved payment history")
                .contains("No credit or overpayment ledger")
                .contains("Manual input is preserved")
                .contains("CASE F")
                .contains("CASE G");
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

    private record SnapshotRow(
            Long id,
            BigDecimal exchangeRate,
            Integer exchangeRateScale,
            String provider,
            String calculationVersion,
            String status
    ) {
    }
}
