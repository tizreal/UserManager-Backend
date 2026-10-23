package com.tca.UserManager;

import static com.auth0.jwt.algorithms.Algorithm.HMAC512;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.auth0.jwt.JWT;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.support.IntegrationTestBase;

/** Admin-only operations: forbidden for USER, permitted for ADMIN, with safety rules. */
class AuthorizationIT extends IntegrationTestBase {

	@Test
	void regularUserCannotListLookUpOrDeleteUsers() throws Exception {
		User alice = createUser("alice", Role.USER);
		User bob = createUser("bob", Role.USER);
		String token = bearer(tokenFor(alice));

		mvc.perform(get("/user/").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());
		mvc.perform(get("/user/bob").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());
		mvc.perform(delete("/user/" + bob.getUserId()).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isForbidden());

		assertThat(users.findById(bob.getUserId())).isPresent();
	}

	@Test
	void adminCanListAndLookUpUsers() throws Exception {
		User admin = createUser("root", Role.ADMIN);
		createUser("bob", Role.USER);
		String token = bearer(tokenFor(admin));

		mvc.perform(get("/user/").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));
		mvc.perform(get("/user/BOB").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("bob"));
		mvc.perform(get("/user/nobody").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isNotFound());
	}

	@Test
	void adminCanDeleteAUserWithoutContent_andTheirTokenStopsWorking() throws Exception {
		User admin = createUser("root", Role.ADMIN);
		User bob = createUser("bob", Role.USER);
		String bobToken = tokenFor(bob);

		mvc.perform(delete("/user/" + bob.getUserId()).header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(admin))))
				.andExpect(status().isOk());

		assertThat(users.findById(bob.getUserId())).isEmpty();
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer(bobToken)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void deletingAUserWithContentIsRefusedWithoutCascading() throws Exception {
		User admin = createUser("root", Role.ADMIN);
		User bob = createUser("bob", Role.USER);
		var post = createPost(bob, "bob's post");

		mvc.perform(delete("/user/" + bob.getUserId()).header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(admin))))
				.andExpect(status().isConflict());

		assertThat(users.findById(bob.getUserId())).isPresent();
		assertThat(posts.findById(post.getId())).isPresent();
	}

	@Test
	void adminCannotDeleteThemselves() throws Exception {
		User admin = createUser("root", Role.ADMIN);
		mvc.perform(delete("/user/" + admin.getUserId()).header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(admin))))
				.andExpect(status().isConflict());
		assertThat(users.findById(admin.getUserId())).isPresent();
	}

	@Test
	void deletingUnknownUserIs404() throws Exception {
		User admin = createUser("root", Role.ADMIN);
		mvc.perform(delete("/user/9999").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(admin))))
				.andExpect(status().isNotFound());
	}

	@Test
	void roleIsReadFromTheDatabaseNotFromTheTokenClaim() throws Exception {
		User alice = createUser("alice", Role.USER);
		// A correctly signed token whose (UI-only) role claim says ADMIN.
		String inflated = JWT.create().withIssuer("BFN-TCA Bootcamp").withAudience("User Manager")
				.withSubject("alice").withClaim("uid", alice.getUserId()).withClaim("pur", "access")
				.withClaim("ver", 0).withClaim("role", "ADMIN")
				.withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
				.sign(HMAC512("integration-test-secret-integration-test-secret-integration-test-secret"));
		mvc.perform(get("/user/").header(HttpHeaders.AUTHORIZATION, bearer(inflated)))
				.andExpect(status().isForbidden());
	}

	@Test
	void roleChangesApplyImmediatelyToExistingTokens() throws Exception {
		User alice = createUser("alice", Role.USER);
		String token = bearer(tokenFor(alice));
		mvc.perform(get("/user/").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());

		alice.setRole(Role.ADMIN);
		users.saveAndFlush(alice);
		mvc.perform(get("/user/").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());

		alice.setRole(Role.USER);
		users.saveAndFlush(alice);
		mvc.perform(get("/user/").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());
	}
}
