package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises the Flyway migrations directly against throwaway PostgreSQL databases,
 * including the production scenario: an existing, populated database with no Flyway
 * history that must be baselined at V1 and then upgraded.
 */
class MigrationIT {

	@SuppressWarnings("resource")
	private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

	static {
		PG.start();
	}

	private static String newDatabase() throws Exception {
		String name = "db_" + UUID.randomUUID().toString().replace("-", "");
		try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
				Statement s = c.createStatement()) {
			s.execute("CREATE DATABASE " + name);
		}
		return PG.getJdbcUrl().replace("/" + PG.getDatabaseName(), "/" + name);
	}

	private static Flyway flyway(String url) {
		// Same settings as application.properties
		return Flyway.configure().dataSource(url, PG.getUsername(), PG.getPassword())
				.baselineOnMigrate(true).baselineVersion("1").load();
	}

	private static void execute(String url, String sql) throws Exception {
		try (Connection c = DriverManager.getConnection(url, PG.getUsername(), PG.getPassword());
				Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	private static String v1Sql() throws Exception {
		return new ClassPathResource("db/migration/V1__baseline_schema.sql").getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	void freshDatabaseGetsTheFullSchema() throws Exception {
		String url = newDatabase();
		var result = flyway(url).migrate();
		assertThat(result.migrationsExecuted).isEqualTo(4);
		assertThat(result.targetSchemaVersion).isEqualTo("4");
	}

	@Test
	void existingPopulatedDatabaseIsBaselinedAndUpgradedWithoutDataLoss() throws Exception {
		String url = newDatabase();
		// Simulate production: the pre-Flyway schema with data, and no flyway_schema_history.
		execute(url, v1Sql());
		execute(url, "INSERT INTO users (user_name, email, password, email_verified, created_at) "
				+ "VALUES ('legacy', 'legacy@example.com', '$2a$10$hash', true, now())");
		execute(url, "INSERT INTO posts (content, user_id, likes_count, created_at) VALUES ('old post', 1, 3, now())");

		var result = flyway(url).migrate();
		assertThat(result.migrationsExecuted).isEqualTo(3); // V2-V4 only; V1 is the baseline

		try (Connection c = DriverManager.getConnection(url, PG.getUsername(), PG.getPassword());
				Statement s = c.createStatement()) {
			ResultSet user = s.executeQuery("SELECT user_name, email, password, role, token_version FROM users");
			assertThat(user.next()).isTrue();
			assertThat(user.getString("user_name")).isEqualTo("legacy");
			assertThat(user.getString("email")).isEqualTo("legacy@example.com");
			assertThat(user.getString("password")).isEqualTo("$2a$10$hash");
			assertThat(user.getString("role")).isEqualTo("USER");
			assertThat(user.getInt("token_version")).isZero();

			ResultSet post = s.executeQuery("SELECT content, likes_count FROM posts");
			assertThat(post.next()).isTrue();
			assertThat(post.getString("content")).isEqualTo("old post");
			assertThat(post.getInt("likes_count")).isEqualTo(3);
		}

		// Running again is a no-op.
		assertThat(flyway(url).migrate().migrationsExecuted).isZero();
	}

	@Test
	void caseInsensitiveDuplicatesStopTheMigrationSafely() throws Exception {
		String url = newDatabase();
		execute(url, v1Sql());
		execute(url, "INSERT INTO users (user_name, email) VALUES ('alice', 'a1@example.com'), ('Alice', 'a2@example.com')");

		assertThatThrownBy(() -> flyway(url).migrate()).isInstanceOf(FlywayException.class);

		// PostgreSQL DDL is transactional: the failed V2 left no partial changes behind.
		try (Connection c = DriverManager.getConnection(url, PG.getUsername(), PG.getPassword());
				Statement s = c.createStatement()) {
			ResultSet cols = s.executeQuery("SELECT count(*) FROM information_schema.columns "
					+ "WHERE table_name = 'users' AND column_name IN ('role', 'token_version')");
			cols.next();
			assertThat(cols.getInt(1)).isZero();
		}
	}
}
