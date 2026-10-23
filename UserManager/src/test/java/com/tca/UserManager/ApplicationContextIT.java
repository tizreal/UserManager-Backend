package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tca.UserManager.support.IntegrationTestBase;

/**
 * The application starts against a database built only by the Flyway migrations, and
 * Hibernate's schema validation (ddl-auto=validate) accepts it.
 */
class ApplicationContextIT extends IntegrationTestBase {

	@Test
	void contextLoadsAndSchemaMatchesTheEntities() {
		assertThat(jdbc.queryForObject("SELECT max(version) FROM flyway_schema_history", String.class)).isEqualTo("3");
	}
}
