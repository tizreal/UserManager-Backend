package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import com.tca.UserManager.config.DemoDataSeeder;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.support.IntegrationTestBase;

/** The demo seeder keeps the shared demo account safe and clears what visitors posted. */
class DemoSeederIT extends IntegrationTestBase {

	@Autowired DemoDataSeeder seeder;

	private void runSeeder() throws Exception {
		ReflectionTestUtils.setField(seeder, "seedDemo", true);
		ReflectionTestUtils.setField(seeder, "demoPassword", "public-demo-password");
		try {
			seeder.run();
		} finally {
			ReflectionTestUtils.setField(seeder, "seedDemo", false);
		}
	}

	@Test
	void seedingPinsTheDemoRoleAndPasswordWithoutTouchingContent() throws Exception {
		User demo = createUser("demo", Role.ADMIN); // e.g. promoted by mistake
		var demoPost = createPost(demo, "visitor post");

		runSeeder();

		User demoAfter = users.findByUsername("demo").orElseThrow();
		assertThat(demoAfter.getRole()).isEqualTo(Role.USER);
		assertThat(passwordEncoder.matches("public-demo-password", demoAfter.getPassword())).isTrue();
		// Content cleanup is a separate, explicit/scheduled job (DemoContentCleanupService)
		assertThat(posts.findById(demoPost.getId())).isPresent();
	}

	@Test
	void fillerAccountsDoNotShareTheDemoPassword() throws Exception {
		runSeeder();
		for (String filler : new String[] { "ada", "alan", "grace" }) {
			User u = users.findByUsername(filler).orElseThrow();
			assertThat(passwordEncoder.matches("public-demo-password", u.getPassword())).isFalse();
		}
	}
}
