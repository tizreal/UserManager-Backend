package com.tca.UserManager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.tca.UserManager.entity.Role;
import com.tca.UserManager.support.IntegrationTestBase;

/** Abuse limits on authentication, signup, reset, and content creation. */
class RateLimitIT extends IntegrationTestBase {

	private MockHttpServletRequestBuilder fromIp(MockHttpServletRequestBuilder builder, String ip) {
		return builder.with(request -> {
			request.setRemoteAddr(ip);
			return request;
		});
	}

	@Test
	void repeatedLoginAttemptsForOneAccountAreThrottled() throws Exception {
		createUser("alice", Role.USER);
		String body = toJson(Map.of("username", "alice", "password", "wrong-password"));
		for (int i = 0; i < 10; i++) {
			// Different IPs: the per-account limit applies regardless of source
			mvc.perform(fromIp(post("/user/login"), "10.0.0." + i).contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(fromIp(post("/user/login"), "10.0.0.99").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists(HttpHeaders.RETRY_AFTER));
	}

	@Test
	void signupsFromOneAddressAreThrottled() throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(fromIp(post("/user/signup"), "203.0.113.7").contentType(MediaType.APPLICATION_JSON)
					.content(toJson(Map.of("username", "user" + i, "email", "user" + i + "@example.com",
							"password", "password-" + i))))
					.andExpect(status().isOk());
		}
		mvc.perform(fromIp(post("/user/signup"), "203.0.113.7").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("username", "user5", "email", "user5@example.com", "password", "password-5"))))
				.andExpect(status().isTooManyRequests());
		// Another address is unaffected
		mvc.perform(fromIp(post("/user/signup"), "198.51.100.1").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("username", "user6", "email", "user6@example.com", "password", "password-6"))))
				.andExpect(status().isOk());
	}

	@Test
	void resetEmailsForOneAccountAreThrottled() throws Exception {
		createUser("alice", Role.USER);
		for (int i = 0; i < 3; i++) {
			mvc.perform(fromIp(get("/user/reset/alice@example.com"), "10.1.0." + i)).andExpect(status().isOk());
		}
		mvc.perform(fromIp(get("/user/reset/alice@example.com"), "10.1.0.9")).andExpect(status().isTooManyRequests());
	}

	@Test
	void postCreationIsThrottledPerUser() throws Exception {
		String token = bearer(tokenFor(createUser("alice", Role.USER)));
		for (int i = 0; i < 20; i++) {
			mvc.perform(post("/posts/create").header(HttpHeaders.AUTHORIZATION, token)
					.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("content", "post " + i))))
					.andExpect(status().isOk());
		}
		mvc.perform(post("/posts/create").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("content", "one too many"))))
				.andExpect(status().isTooManyRequests());
	}
}
