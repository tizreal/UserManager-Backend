package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.support.IntegrationTestBase;

/** The public demo login is a plain USER that cannot change its credentials or reach other users' data. */
class DemoAccountIT extends IntegrationTestBase {

	@Test
	void demoAccountHasNoAdministrativeAccess() throws Exception {
		User demo = createUser("demo", Role.USER);
		User alice = createUser("alice", Role.USER);
		String token = bearer(login("demo", PASSWORD));

		mvc.perform(get("/user/").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());
		mvc.perform(get("/user/alice").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());
		mvc.perform(delete("/user/" + alice.getUserId()).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isForbidden());
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("demo"))
				.andExpect(jsonPath("$.userId").value(demo.getUserId()));
	}

	@Test
	void demoCredentialsAndProfileCannotBeChanged() throws Exception {
		User demo = createUser("demo", Role.USER);
		String token = bearer(tokenFor(demo));
		for (Map<String, String> change : new Map[] {
				Map.of("password", "hijacked-password"),
				Map.of("email", "attacker@example.com"),
				Map.of("firstName", "Defaced") }) {
			mvc.perform(post("/user/update").header(HttpHeaders.AUTHORIZATION, token)
					.contentType(MediaType.APPLICATION_JSON).content(toJson(change)))
					.andExpect(status().isForbidden());
		}
		User after = users.findById(demo.getUserId()).orElseThrow();
		assertThat(passwordEncoder.matches(PASSWORD, after.getPassword())).isTrue();
		assertThat(after.getEmail()).isEqualTo("demo@example.com");
		assertThat(after.getFirstName()).isEqualTo("Demo");
		assertThat(after.getTokenVersion()).isZero();
	}

	@Test
	void demoAccountNeverReceivesPasswordResetLinks() throws Exception {
		createUser("demo", Role.USER);
		mvc.perform(get("/user/reset/demo@example.com")).andExpect(status().isOk());
		verify(emailService, never()).sendResetPasswordEmail(any(), anyString());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens", Integer.class)).isZero();
	}

	@Test
	void demoCannotDeleteOtherUsersPostsButCanManageItsOwn() throws Exception {
		User demo = createUser("demo", Role.USER);
		User alice = createUser("alice", Role.USER);
		var alicePost = createPost(alice, "alice's post");
		var demoPost = createPost(demo, "demo's post");
		String token = bearer(tokenFor(demo));

		mvc.perform(delete("/posts/" + alicePost.getId()).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isForbidden());
		mvc.perform(delete("/posts/" + demoPost.getId()).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk());
		assertThat(posts.findById(alicePost.getId())).isPresent();
	}

	@Test
	void demoCannotSeeOtherUsersPrivateDetailsInTheFeed() throws Exception {
		User demo = createUser("demo", Role.USER);
		User alice = createUser("alice", Role.USER);
		createPost(alice, "alice's post");
		String feed = mvc.perform(get("/posts/").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(demo))))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(feed).doesNotContain("alice@example.com").doesNotContain(alice.getPhoneNumber());
	}

	@Test
	void promotedDemoAccountStillCannotChangeCredentials() throws Exception {
		// Even if the demo account were promoted by mistake, its credentials stay locked.
		User demo = createUser("demo", Role.ADMIN);
		mvc.perform(post("/user/update").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(demo)))
				.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("password", "hijacked-password"))))
				.andExpect(status().isForbidden());
	}
}
