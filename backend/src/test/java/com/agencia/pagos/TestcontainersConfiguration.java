package com.agencia.pagos;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Disposable PostgreSQL container for integration tests.
 *
 * <p>Pins the PostgreSQL major/minor version to {@code postgres:17.4} so the
 * schema used by tests matches the production root compose stack
 * ({@code postgres:17.4}). Running {@code postgres:latest} would silently
 * change the engine between executions and break determinism for Flyway
 * migrations and {@code hstore} / JSONB column quirks.
 *
 * <p>This bean never connects to the developer root compose DB; Testcontainers
 * will spin a fresh container per JVM and destroy it on shutdown, so tests that
 * call broad {@code deleteAll()} cannot erase development data.
 *
 * <p>Uses the preferred {@code org.testcontainers.postgresql.PostgreSQLContainer}
 * class shipped with Testcontainers 2.x ({@code testcontainers-postgresql}
 * artifact). The legacy {@code org.testcontainers.containers.PostgreSQLContainer}
 * remains available as a compatibility alias but is not used here.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17.4"));
	}

}
