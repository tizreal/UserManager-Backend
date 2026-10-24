package com.tca.UserManager.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Documents which environment variable names turn on DemoDataSeeder, whose flag is read
 * with @Value("${app.seed-demo:false}"). OS environment variables are resolved through
 * SystemEnvironmentPropertySource, which tries the name with '.' and '-' replaced by '_'
 * and upper-cased.
 */
class SeedDemoPropertyResolutionTest {

	private static String resolveWith(Map<String, Object> env) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
				new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
		return environment.resolvePlaceholders("${app.seed-demo:false}");
	}

	@Test
	void appSeedDemoEnvironmentVariableEnablesSeeding() {
		assertThat(resolveWith(Map.of("APP_SEED_DEMO", "true"))).isEqualTo("true");
	}

	@Test
	void lowercaseUnderscoreFormAlsoWorks() {
		assertThat(resolveWith(Map.of("app_seed_demo", "true"))).isEqualTo("true");
	}

	@Test
	void namesThatDoNotMapLeaveSeedingOff() {
		// Dashes are not dropped for @Value lookups, so these silently have no effect.
		assertThat(resolveWith(Map.of("APP_SEEDDEMO", "true"))).isEqualTo("false");
		assertThat(resolveWith(Map.of("SEED_DEMO", "true"))).isEqualTo("false");
	}

	@Test
	void unsetMeansNoSeeding() {
		assertThat(resolveWith(Map.of())).isEqualTo("false");
	}
}
