package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.tca.UserManager.dto.SignupRequest;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.service.UserService;
import com.tca.UserManager.support.IntegrationTestBase;

/** The signup and profile-update paths can no longer overwrite or escalate accounts. */
class AccountTakeoverIT extends IntegrationTestBase {

	@Autowired UserService userService;

	private Map<String, Object> signup(String username, String email) {
		Map<String, Object> body = new HashMap<>();
		body.put("username", username);
		body.put("email", email);
		body.put("password", "attacker-password");
		body.put("firstName", "Mal");
		body.put("lastName", "Lory");
		return body;
	}

	@Test
	void signupWithAnExistingUserIdCreatesANewAccountAndLeavesTheVictimUntouched() throws Exception {
		User victim = createUser("victim", Role.ADMIN);
		String victimHash = victim.getPassword();

		Map<String, Object> body = signup("mallory", "mallory@example.com");
		body.put("userId", victim.getUserId());
		body.put("role", "ADMIN");
		body.put("emailVerified", true);
		body.put("tokenVersion", 99);

		mvc.perform(post("/user/signup").contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("mallory"))
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.emailVerified").value(false));

		User after = users.findById(victim.getUserId()).orElseThrow();
		assertThat(after.getUsername()).isEqualTo("victim");
		assertThat(after.getEmail()).isEqualTo("victim@example.com");
		assertThat(after.getPassword()).isEqualTo(victimHash);
		assertThat(after.getRole()).isEqualTo(Role.ADMIN);

		User mallory = users.findByUsername("mallory").orElseThrow();
		assertThat(mallory.getUserId()).isNotEqualTo(victim.getUserId());
		assertThat(mallory.getRole()).isEqualTo(Role.USER);
		assertThat(mallory.getEmailVerified()).isFalse();
		assertThat(mallory.getTokenVersion()).isZero();
	}

	@Test
	void duplicateUsernameOrEmailIsRejectedCaseInsensitively() throws Exception {
		createUser("alice", Role.USER);
		mvc.perform(post("/user/signup").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(signup("ALICE", "new@example.com")))).andExpect(status().isBadRequest());
		mvc.perform(post("/user/signup").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(signup("alice2", "ALICE@EXAMPLE.COM")))).andExpect(status().isBadRequest());
		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void databaseEnforcesCaseInsensitiveUniqueness() {
		createUser("alice", Role.USER);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO users (user_name, email, password, email_verified, role, token_version) "
						+ "VALUES ('Alice', 'other@example.com', 'x', true, 'USER', 0)"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO users (user_name, email, password, email_verified, role, token_version) "
						+ "VALUES ('someone', 'Alice@Example.com', 'x', true, 'USER', 0)"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsUnknownRoles() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO users (user_name, email, password, email_verified, role, token_version) "
						+ "VALUES ('x', 'x@example.com', 'x', true, 'SUPERUSER', 0)"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void concurrentSignupsForTheSameUsernameCreateExactlyOneAccount() throws Exception {
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			int n = i;
			results.add(pool.submit(() -> {
				start.await();
				try {
					userService.signup(new SignupRequest("racer", "racer" + n + "@example.com",
							"password-" + n, "R", "Acer", null, null));
					return true;
				} catch (RuntimeException expected) {
					return false;
				}
			}));
		}
		start.countDown();
		int successes = 0;
		for (Future<Boolean> f : results) {
			if (f.get(30, TimeUnit.SECONDS)) successes++;
		}
		pool.shutdown();

		assertThat(successes).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(user_name) = 'racer'", Integer.class))
				.isEqualTo(1);
	}

	@Test
	void profileUpdateIgnoresPrivilegedFields() throws Exception {
		User alice = createUser("alice", Role.USER);
		User bob = createUser("bob", Role.USER);
		Map<String, Object> body = new HashMap<>();
		body.put("firstName", "Alicia");
		body.put("userId", bob.getUserId());
		body.put("username", "bob");
		body.put("role", "ADMIN");
		body.put("emailVerified", false);
		body.put("tokenVersion", 42);

		mvc.perform(post("/user/update").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(alice)))
				.contentType(MediaType.APPLICATION_JSON).content(toJson(body)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("alice"))
				.andExpect(jsonPath("$.firstName").value("Alicia"))
				.andExpect(jsonPath("$.role").value("USER"));

		User aliceAfter = users.findById(alice.getUserId()).orElseThrow();
		assertThat(aliceAfter.getRole()).isEqualTo(Role.USER);
		assertThat(aliceAfter.getEmailVerified()).isTrue();
		assertThat(aliceAfter.getTokenVersion()).isZero();
		User bobAfter = users.findById(bob.getUserId()).orElseThrow();
		assertThat(bobAfter.getFirstName()).isEqualTo("Bob");
	}

	@Test
	void profileUpdateCannotTakeAnotherUsersEmail() throws Exception {
		User alice = createUser("alice", Role.USER);
		createUser("bob", Role.USER);
		mvc.perform(post("/user/update").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(alice)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("email", "BOB@example.com", "currentPassword", PASSWORD))))
				.andExpect(status().isBadRequest());
		assertThat(users.findById(alice.getUserId()).orElseThrow().getEmail()).isEqualTo("alice@example.com");
	}

	@Test
	void signupValidatesInput() throws Exception {
		Map<String, Object> shortPassword = signup("newbie", "newbie@example.com");
		shortPassword.put("password", "short");
		mvc.perform(post("/user/signup").contentType(MediaType.APPLICATION_JSON).content(toJson(shortPassword)))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/user/signup").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(signup("bad name!", "newbie@example.com")))).andExpect(status().isBadRequest());
		mvc.perform(post("/user/signup").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(signup("newbie", "not-an-email")))).andExpect(status().isBadRequest());
		assertThat(users.count()).isZero();
	}
}
