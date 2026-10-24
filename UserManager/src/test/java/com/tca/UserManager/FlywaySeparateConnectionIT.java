package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

import com.tca.UserManager.service.EmailService;

/**
 * Production may need Flyway to use a different connection than the application, e.g. Neon's
 * direct (non-pooled) endpoint and/or the table-owner role, configured with
 * SPRING_FLYWAY_URL / SPRING_FLYWAY_USER / SPRING_FLYWAY_PASSWORD. This test proves those
 * settings are honored: the application connects as one role, Flyway as "migrator", and the
 * migration history and table ownership show "migrator".
 */
@SpringBootTest(properties = {
		"JWT_SECRET=integration-test-secret-integration-test-secret-integration-test-secret",
		"RESEND_API_KEY=test-key", "MAIL_HOST=localhost", "MAIL_USERNAME=test", "MAIL_PASSWORD=test",
		"DATABASE_URL=jdbc:postgresql://overridden", "DATABASE_USERNAME=overridden", "DATABASE_PASSWORD=overridden",
		"app.seed-demo=false" })
class FlywaySeparateConnectionIT {

	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
	static String dbUrl;

	static {
		PG.start();
		try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
				Statement s = c.createStatement()) {
			s.execute("CREATE ROLE migrator LOGIN PASSWORD 'migrator-pw'");
			// On PostgreSQL 15+, the "public" schema belongs to the database owner.
			s.execute("CREATE DATABASE app_db OWNER migrator");
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		dbUrl = PG.getJdbcUrl().replace("/" + PG.getDatabaseName(), "/app_db");
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		// Application connection: a different role (the container superuser here)
		registry.add("spring.datasource.url", () -> dbUrl);
		registry.add("spring.datasource.username", PG::getUsername);
		registry.add("spring.datasource.password", PG::getPassword);
		// Flyway connection: what SPRING_FLYWAY_URL / _USER / _PASSWORD would set
		registry.add("spring.flyway.url", () -> dbUrl + "?ApplicationName=flyway-direct");
		registry.add("spring.flyway.user", () -> "migrator");
		registry.add("spring.flyway.password", () -> "migrator-pw");
	}

	@MockitoBean EmailService emailService;
	@Autowired JdbcTemplate jdbc;

	@Test
	void migrationsRunThroughTheFlywayConnectionAndTheAppStillValidates() {
		List<String> installedBy = jdbc.queryForList(
				"SELECT DISTINCT installed_by FROM flyway_schema_history", String.class);
		assertThat(installedBy).containsExactly("migrator");

		List<String> owners = jdbc.queryForList(
				"SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'", String.class);
		assertThat(owners).containsExactly("migrator");

		// The application context started, so Hibernate validation passed on the migrated schema.
		assertThat(jdbc.queryForObject("SELECT max(version) FROM flyway_schema_history", String.class)).isEqualTo("4");
	}
}
