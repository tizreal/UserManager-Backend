package com.tca.UserManager;

import static com.auth0.jwt.algorithms.Algorithm.HMAC512;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import com.auth0.jwt.JWT;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.support.IntegrationTestBase;

class AuthenticationIT extends IntegrationTestBase {

	@ParameterizedTest(name = "{0} {1} without a token -> 401")
	@CsvSource({
			"GET, /user/get", "POST, /user/update", "GET, /user/", "GET, /user/alice", "DELETE, /user/1",
			"GET, /posts/", "POST, /posts/create", "POST, /posts/1/like", "POST, /posts/1/unlike",
			"DELETE, /posts/1", "POST, /comments/create", "GET, /comments/1" })
	void protectedEndpointsRequireAuthentication(String method, String path) throws Exception {
		mvc.perform(request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void malformedTokenIsRejected() throws Exception {
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void tokenSignedWithAnotherSecretIsRejected() throws Exception {
		User alice = createUser("alice", Role.USER);
		String forged = JWT.create().withIssuer("BFN-TCA Bootcamp").withAudience("User Manager")
				.withSubject("alice").withClaim("uid", alice.getUserId()).withClaim("pur", "access")
				.withClaim("ver", 0).withClaim("role", "ADMIN")
				.withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
				.sign(HMAC512("some-other-secret"));
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer(forged)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void emailVerificationTokenCannotBeUsedAsSession() throws Exception {
		User alice = createUser("alice", Role.USER);
		String verificationToken = jwtService.generateEmailVerificationToken(alice);
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer(verificationToken)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void legacyTokenWithoutPurposeIsRejected() throws Exception {
		// Shape of tokens issued before this change (no "pur"/"uid" claims).
		String legacy = JWT.create().withIssuer("BFN-TCA Bootcamp").withAudience("User Manager")
				.withSubject("alice").withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
				.sign(HMAC512("integration-test-secret-integration-test-secret-integration-test-secret"));
		createUser("alice", Role.USER);
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer(legacy)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void deletedUsersTokenStopsWorking() throws Exception {
		User alice = createUser("alice", Role.USER);
		String token = tokenFor(alice);
		users.deleteById(alice.getUserId());
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void revokedSessionTokenStopsWorking() throws Exception {
		User alice = createUser("alice", Role.USER);
		String token = tokenFor(alice);
		alice.incrementTokenVersion();
		users.saveAndFlush(alice);
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void unverifiedUserCannotLogInOrUseATokenMintedForThem() throws Exception {
		User bob = createUser("bob", Role.USER, false);
		mvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("username", "bob", "password", PASSWORD))))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer(jwtService.generateAccessToken(bob))))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void wrongPasswordIsRejectedAndUsernameIsCaseInsensitive() throws Exception {
		createUser("alice", Role.USER);
		mvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("username", "alice", "password", "wrong-password"))))
				.andExpect(status().isBadRequest());
		String token = login("ALICE", PASSWORD);
		assertThat(token).isNotBlank();
	}

	@Test
	void loginReturnsTokenAndProfileWithoutCredentials() throws Exception {
		createUser("alice", Role.USER);
		mvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("username", "alice", "password", PASSWORD))))
				.andExpect(status().isOk())
				.andExpect(header().exists(HttpHeaders.AUTHORIZATION))
				.andExpect(jsonPath("$.username").value("alice"))
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.tokenVersion").doesNotExist());
	}

	@Test
	void publicEndpointsStillWorkWhenAStaleTokenIsSent() throws Exception {
		createUser("alice", Role.USER);
		mvc.perform(post("/user/login").header(HttpHeaders.AUTHORIZATION, bearer("stale"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("username", "alice", "password", PASSWORD))))
				.andExpect(status().isOk());
		mvc.perform(get("/health")).andExpect(status().isOk());
	}

	@Test
	void unknownDeleteTargetStillRequiresAuthentication() throws Exception {
		mvc.perform(delete("/user/999")).andExpect(status().isUnauthorized());
	}
}
