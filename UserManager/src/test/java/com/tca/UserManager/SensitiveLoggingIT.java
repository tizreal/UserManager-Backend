package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.support.IntegrationTestBase;

/**
 * Even with DEBUG logging enabled, access tokens, reset tokens and passwords never
 * appear in the logs.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = { "LOG_LEVEL=DEBUG", "WEB_LOG_LEVEL=DEBUG",
		"logging.level.org.springframework.security=DEBUG" })
class SensitiveLoggingIT extends IntegrationTestBase {

	@Test
	void tokensAndPasswordsAreNeverLogged(CapturedOutput output) throws Exception {
		User alice = createUser("alice", Role.USER);

		String accessToken = login("alice", PASSWORD);
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isOk());
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer("tampered." + accessToken)))
				.andExpect(status().isUnauthorized());

		mvc.perform(get("/user/reset/alice@example.com")).andExpect(status().isOk());
		ArgumentCaptor<String> resetToken = ArgumentCaptor.forClass(String.class);
		verify(emailService).sendResetPasswordEmail(any(User.class), resetToken.capture());
		mvc.perform(post("/user/reset").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("token", resetToken.getValue(), "password", "post-reset-password"))))
				.andExpect(status().isOk());

		String newSession = login("alice", "post-reset-password");
		mvc.perform(post("/user/update").header(HttpHeaders.AUTHORIZATION, bearer(newSession))
				.contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("password", "changed-again-password", "currentPassword", "post-reset-password"))))
				.andExpect(status().isOk());

		String logs = output.getAll();
		// Sanity check: the filter's DEBUG line for the tampered token proves debug logging was on.
		assertThat(logs).as("debug logging was active").contains("Rejected access token");
		assertThat(logs)
				.doesNotContain(accessToken)
				.doesNotContain(newSession)
				.doesNotContain(resetToken.getValue())
				.doesNotContain(PASSWORD)
				.doesNotContain("post-reset-password")
				.doesNotContain("changed-again-password")
				.doesNotContain(alice.getPassword());
	}
}
