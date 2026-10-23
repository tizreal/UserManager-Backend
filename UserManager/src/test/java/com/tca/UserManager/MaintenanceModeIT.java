package com.tca.UserManager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.tca.UserManager.entity.Role;
import com.tca.UserManager.support.IntegrationTestBase;

/** With maintenance mode on, only /health and requests carrying the bypass key get through. */
@TestPropertySource(properties = { "MAINTENANCE_MODE=true", "MAINTENANCE_BYPASS_KEY=test-bypass-key" })
class MaintenanceModeIT extends IntegrationTestBase {

	private static final String LOGIN = "{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}";

	@Test
	void publicTrafficGets503ButHealthChecksWork() throws Exception {
		mvc.perform(get("/health")).andExpect(status().isOk());
		mvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
				.andExpect(status().isServiceUnavailable());
		mvc.perform(get("/posts/")).andExpect(status().isServiceUnavailable());
	}

	@Test
	void theBypassKeyAllowsPrivateSmokeTests() throws Exception {
		createUser("alice", Role.USER);
		mvc.perform(post("/user/login").header("X-Maintenance-Key", "test-bypass-key")
				.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("username", "alice", "password", PASSWORD))))
				.andExpect(status().isOk());
		mvc.perform(post("/user/login").header("X-Maintenance-Key", "wrong-key")
				.contentType(MediaType.APPLICATION_JSON).content(LOGIN))
				.andExpect(status().isServiceUnavailable());
	}
}
