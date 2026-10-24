package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.support.IntegrationTestBase;

/** Password and email changes require the current password; new emails must be confirmed. */
class CredentialChangeIT extends IntegrationTestBase {

	private org.springframework.test.web.servlet.ResultActions update(User user, Map<String, Object> body)
			throws Exception {
		return mvc.perform(post("/user/update").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(user)))
				.contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
	}

	@Test
	void passwordChangeRequiresTheCorrectCurrentPassword() throws Exception {
		User alice = createUser("alice", Role.USER);
		update(alice, Map.of("password", "new-password-123")).andExpect(status().isBadRequest());
		update(alice, Map.of("password", "new-password-123", "currentPassword", "wrong-password"))
				.andExpect(status().isBadRequest());
		assertThat(passwordEncoder.matches(PASSWORD, users.findById(alice.getUserId()).orElseThrow().getPassword()))
				.isTrue();

		update(alice, Map.of("password", "new-password-123", "currentPassword", PASSWORD)).andExpect(status().isOk());
		assertThat(login("alice", "new-password-123")).isNotBlank();
	}

	@Test
	void nonCredentialFieldsDoNotNeedTheCurrentPassword() throws Exception {
		User alice = createUser("alice", Role.USER);
		update(alice, Map.of("firstName", "Alicia", "email", "alice@example.com")).andExpect(status().isOk())
				.andExpect(jsonPath("$.firstName").value("Alicia"));
	}

	@Test
	void emailChangeIsPendingUntilConfirmedFromTheNewInbox() throws Exception {
		User alice = createUser("alice", Role.USER);
		update(alice, Map.of("email", "new@example.com")).andExpect(status().isBadRequest()); // no current password

		update(alice, Map.of("email", "New@Example.com", "currentPassword", PASSWORD))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("alice@example.com"))
				.andExpect(jsonPath("$.pendingEmail").value("new@example.com"));

		ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
		verify(emailService).sendVerificationEmail(any(User.class), eq("new@example.com"), token.capture());

		// Until confirmed, the new address is not usable for password reset
		mvc.perform(get("/user/reset/new@example.com")).andExpect(status().isOk());
		verify(emailService, never()).sendResetPasswordEmail(any(), anyString());

		mvc.perform(get("/user/verify/email").header(HttpHeaders.AUTHORIZATION, bearer(token.getValue())))
				.andExpect(status().isOk());
		User after = users.findById(alice.getUserId()).orElseThrow();
		assertThat(after.getEmail()).isEqualTo("new@example.com");
		assertThat(after.getPendingEmail()).isNull();
		assertThat(after.getEmailVerified()).isTrue();
	}

	@Test
	void confirmingAnAddressSomeoneElseClaimedInTheMeantimeFails() throws Exception {
		User alice = createUser("alice", Role.USER);
		update(alice, Map.of("email", "contested@example.com", "currentPassword", PASSWORD)).andExpect(status().isOk());
		ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
		verify(emailService).sendVerificationEmail(any(User.class), eq("contested@example.com"), token.capture());

		mvc.perform(post("/user/signup").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("username", "carol", "email", "contested@example.com", "password", "carol-password"))))
				.andExpect(status().isOk());

		mvc.perform(get("/user/verify/email").header(HttpHeaders.AUTHORIZATION, bearer(token.getValue())))
				.andExpect(status().isBadRequest());
		assertThat(users.findById(alice.getUserId()).orElseThrow().getEmail()).isEqualTo("alice@example.com");
	}

	@Test
	void aConfirmationLinkForAnOlderPendingAddressIsRejected() throws Exception {
		User alice = createUser("alice", Role.USER);
		update(alice, Map.of("email", "first@example.com", "currentPassword", PASSWORD)).andExpect(status().isOk());
		String firstToken = jwtService.generateEmailVerificationToken(
				users.findById(alice.getUserId()).orElseThrow(), "first@example.com");
		update(alice, Map.of("email", "second@example.com", "currentPassword", PASSWORD)).andExpect(status().isOk());

		mvc.perform(get("/user/verify/email").header(HttpHeaders.AUTHORIZATION, bearer(firstToken)))
				.andExpect(status().isBadRequest());
		assertThat(users.findById(alice.getUserId()).orElseThrow().getEmail()).isEqualTo("alice@example.com");
	}

	@Test
	void currentPasswordGuessingIsRateLimited() throws Exception {
		User alice = createUser("alice", Role.USER);
		Map<String, Object> body = new HashMap<>(Map.of("password", "new-password-123", "currentPassword", "guess"));
		for (int i = 0; i < 10; i++) {
			update(alice, body).andExpect(status().isBadRequest());
		}
		update(alice, body).andExpect(status().isTooManyRequests());
		// Even the right password is refused until the window passes
		body.put("currentPassword", PASSWORD);
		update(alice, body).andExpect(status().isTooManyRequests());
	}
}
