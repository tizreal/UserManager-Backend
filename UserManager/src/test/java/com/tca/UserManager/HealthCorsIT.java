package com.tca.UserManager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.tca.UserManager.support.IntegrationTestBase;

/** The portfolio may read /health (its demo-readiness check) and nothing else. */
class HealthCorsIT extends IntegrationTestBase {

	private static final String PORTFOLIO = "https://my-portfolio-nine-omega-88.vercel.app";

	@Test
	void portfolioCanReadHealth() throws Exception {
		mvc.perform(get("/health").header("Origin", PORTFOLIO))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", PORTFOLIO));
	}

	@Test
	void portfolioCannotCallOtherEndpoints() throws Exception {
		mvc.perform(options("/user/login").header("Origin", PORTFOLIO)
				.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isForbidden());
		mvc.perform(get("/posts/").header("Origin", PORTFOLIO))
				.andExpect(status().isForbidden());
	}

	@Test
	void healthAllowsOnlyGetFromThePortfolio() throws Exception {
		mvc.perform(options("/health").header("Origin", PORTFOLIO)
				.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isForbidden());
	}

	@Test
	void frontendKeepsItsAccessAndUnknownOriginsAreRejected() throws Exception {
		mvc.perform(get("/health").header("Origin", "https://usermanager-frontend.vercel.app"))
				.andExpect(header().string("Access-Control-Allow-Origin", "https://usermanager-frontend.vercel.app"));
		mvc.perform(get("/health").header("Origin", "https://evil.example"))
				.andExpect(status().isForbidden());
	}
}
