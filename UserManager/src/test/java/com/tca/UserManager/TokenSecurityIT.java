package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.service.PasswordResetService;
import com.tca.UserManager.support.IntegrationTestBase;

/** Purpose-bound tokens, email verification, and single-use password reset. */
class TokenSecurityIT extends IntegrationTestBase {

	@Autowired PasswordResetService passwordResetService;

	private String requestResetAndCaptureToken(String email) throws Exception {
		Mockito.clearInvocations(emailService);
		mvc.perform(get("/user/reset/" + email)).andExpect(status().isOk());
		ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
		verify(emailService).sendResetPasswordEmail(any(User.class), token.capture());
		return token.getValue();
	}

	private void reset(String token, String password, int expectedStatus) throws Exception {
		mvc.perform(post("/user/reset").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("token", token, "password", password))))
				.andExpect(status().is(expectedStatus));
	}

	// ---- Email verification ----

	@Test
	void verificationTokenVerifiesTheEmailItWasIssuedFor() throws Exception {
		User bob = createUser("bob", Role.USER, false);
		mvc.perform(get("/user/verify/email").header(HttpHeaders.AUTHORIZATION,
				bearer(jwtService.generateEmailVerificationToken(bob)))).andExpect(status().isOk());
		assertThat(users.findById(bob.getUserId()).orElseThrow().getEmailVerified()).isTrue();
	}

	@Test
	void accessTokenCannotVerifyAnEmail() throws Exception {
		User bob = createUser("bob", Role.USER, false);
		mvc.perform(get("/user/verify/email").header(HttpHeaders.AUTHORIZATION,
				bearer(jwtService.generateAccessToken(bob)))).andExpect(status().isBadRequest());
		assertThat(users.findById(bob.getUserId()).orElseThrow().getEmailVerified()).isFalse();
	}

	@Test
	void verificationTokenForAPreviousEmailIsRejected() throws Exception {
		User bob = createUser("bob", Role.USER, false);
		String token = jwtService.generateEmailVerificationToken(bob);
		bob.setEmail("bob-new@example.com");
		users.saveAndFlush(bob);
		mvc.perform(get("/user/verify/email").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void missingVerificationTokenIsRejected() throws Exception {
		mvc.perform(get("/user/verify/email")).andExpect(status().isBadRequest());
	}

	// ---- Password reset ----

	@Test
	void resetWorksOnceAndSignsOutExistingSessions() throws Exception {
		User alice = createUser("alice", Role.USER);
		String oldSession = tokenFor(alice);
		String token = requestResetAndCaptureToken("alice@example.com");

		reset(token, "brand-new-password", 200);
		reset(token, "another-password", 400); // reuse

		login("alice", "brand-new-password");
		mvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("username", "alice", "password", PASSWORD))))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer(oldSession)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void onlyTheHashOfTheResetTokenIsStored() throws Exception {
		createUser("alice", Role.USER);
		String token = requestResetAndCaptureToken("alice@example.com");
		List<String> stored = jdbc.queryForList("SELECT token_hash FROM password_reset_tokens", String.class);
		assertThat(stored).hasSize(1);
		assertThat(stored.get(0)).isNotEqualTo(token).hasSize(64);
	}

	@Test
	void requestingANewResetInvalidatesTheOlderLink() throws Exception {
		createUser("alice", Role.USER);
		String first = requestResetAndCaptureToken("alice@example.com");
		String second = requestResetAndCaptureToken("alice@example.com");
		reset(first, "brand-new-password", 400);
		reset(second, "brand-new-password", 200);
	}

	@Test
	void expiredResetTokenIsRejected() throws Exception {
		createUser("alice", Role.USER);
		String token = requestResetAndCaptureToken("alice@example.com");
		jdbc.update("UPDATE password_reset_tokens SET expires_at = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
		reset(token, "brand-new-password", 400);
	}

	@Test
	void accessTokensAndGarbageCannotBeUsedAsResetTokens() throws Exception {
		User alice = createUser("alice", Role.USER);
		reset(tokenFor(alice), "brand-new-password", 400);
		reset("garbage", "brand-new-password", 400);
		assertThat(passwordEncoder.matches(PASSWORD, users.findById(alice.getUserId()).orElseThrow().getPassword()))
				.isTrue();
	}

	@Test
	void resetRequestRevealsNothingAboutUnknownEmails() throws Exception {
		mvc.perform(get("/user/reset/nobody@example.com")).andExpect(status().isOk());
		verify(emailService, never()).sendResetPasswordEmail(any(), anyString());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens", Integer.class)).isZero();
	}

	@Test
	void resetPasswordMustMeetPolicy() throws Exception {
		createUser("alice", Role.USER);
		String token = requestResetAndCaptureToken("alice@example.com");
		reset(token, "short", 400);
		reset(token, "long-enough-password", 200); // the rejected attempt did not consume it
	}

	@Test
	void concurrentResetsWithTheSameTokenSucceedExactlyOnce() throws Exception {
		User alice = createUser("alice", Role.USER);
		String token = requestResetAndCaptureToken("alice@example.com");

		int threads = 10;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> results = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			String password = "concurrent-password-" + i;
			results.add(pool.submit(() -> {
				start.await();
				try {
					passwordResetService.resetPassword(token, password);
					return password;
				} catch (RuntimeException expected) {
					return null;
				}
			}));
		}
		start.countDown();
		List<String> winners = new ArrayList<>();
		for (Future<String> f : results) {
			String winner = f.get(30, TimeUnit.SECONDS);
			if (winner != null) winners.add(winner);
		}
		pool.shutdown();

		assertThat(winners).hasSize(1);
		User after = users.findById(alice.getUserId()).orElseThrow();
		assertThat(passwordEncoder.matches(winners.get(0), after.getPassword())).isTrue();
		assertThat(after.getTokenVersion()).isEqualTo(1);
	}
}
