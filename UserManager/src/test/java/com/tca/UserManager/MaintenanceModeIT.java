package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.tca.UserManager.entity.Role;
import com.tca.UserManager.support.IntegrationTestBase;

/**
 * With maintenance mode on, only /health and requests carrying the bypass header get
 * through, and the bypass key never appears in logs. The key is only ever sent as a
 * header by an operator (curl); the frontend has no knowledge of it.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = { "MAINTENANCE_MODE=true", "MAINTENANCE_BYPASS_KEY=test-bypass-key-0123456789",
		"LOG_LEVEL=DEBUG", "WEB_LOG_LEVEL=DEBUG", "logging.level.org.springframework.security=DEBUG" })
class MaintenanceModeIT extends IntegrationTestBase {

	private static final String KEY = "test-bypass-key-0123456789";

	@Test
	void healthChecksStillWork() throws Exception {
		mvc.perform(get("/health")).andExpect(status().isOk());
	}

	@ParameterizedTest(name = "{0} {1} -> 503")
	@CsvSource({
			"POST, /user/login", "POST, /user/signup", "GET, /user/reset/alice@example.com", "POST, /user/reset",
			"GET, /user/verify/email", "GET, /user/get", "POST, /user/update", "POST, /user/delete-account",
			"GET, /user/", "DELETE, /user/1", "POST, /user/1/deactivate", "GET, /posts/", "POST, /posts/create",
			"DELETE, /posts/1", "POST, /comments/create", "GET, /comments/1", "POST, /admin/demo-content/cleanup" })
	void everyOtherEndpointIsClosed_evenWithAValidSession(String method, String path) throws Exception {
		var admin = createUser("root", Role.ADMIN);
		mvc.perform(request(HttpMethod.valueOf(method), path)
				.header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(admin)))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isServiceUnavailable());
	}

	@Test
	void theBypassHeaderAllowsPrivateSmokeTests() throws Exception {
		createUser("alice", Role.USER);
		mvc.perform(post("/user/login").header("X-Maintenance-Key", KEY)
				.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("username", "alice", "password", PASSWORD))))
				.andExpect(status().isOk());
	}

	@Test
	void wrongKeysAndNonHeaderChannelsDoNotBypass() throws Exception {
		String login = toJson(Map.of("username", "alice", "password", PASSWORD));
		mvc.perform(post("/user/login").header("X-Maintenance-Key", "wrong-key")
				.contentType(MediaType.APPLICATION_JSON).content(login))
				.andExpect(status().isServiceUnavailable());
		// The key in the URL (query string) or a cookie is ignored
		mvc.perform(post("/user/login").param("X-Maintenance-Key", KEY)
				.contentType(MediaType.APPLICATION_JSON).content(login))
				.andExpect(status().isServiceUnavailable());
		mvc.perform(post("/user/login").cookie(new jakarta.servlet.http.Cookie("X-Maintenance-Key", KEY))
				.contentType(MediaType.APPLICATION_JSON).content(login))
				.andExpect(status().isServiceUnavailable());
	}

	@Test
	void theBypassKeyIsNeverLogged(CapturedOutput output) throws Exception {
		createUser("alice", Role.USER);
		mvc.perform(post("/user/login").header("X-Maintenance-Key", KEY)
				.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("username", "alice", "password", PASSWORD))))
				.andExpect(status().isOk());
		mvc.perform(get("/posts/").header("X-Maintenance-Key", KEY + "-wrong"))
				.andExpect(status().isServiceUnavailable());
		assertThat(output.getAll()).doesNotContain(KEY);
	}
}
