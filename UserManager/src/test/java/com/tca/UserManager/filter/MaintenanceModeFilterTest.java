package com.tca.UserManager.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MaintenanceModeFilterTest {

	@Test
	void anEmptyConfiguredKeyCanNeverBypassMaintenance() throws Exception {
		MaintenanceModeFilter filter = new MaintenanceModeFilter(true, "");
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/posts/");
		request.setServletPath("/posts/");
		request.addHeader(MaintenanceModeFilter.BYPASS_HEADER, "");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, new MockFilterChain());

		assertThat(response.getStatus()).isEqualTo(503);
	}

	@Test
	void healthCheckPassesUnderTheApiContextPath() throws Exception {
		MaintenanceModeFilter filter = new MaintenanceModeFilter(true, "key");
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
		request.setContextPath("/api");
		request.setServletPath("/health");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest()).isNotNull();
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void disabledMaintenanceLetsEverythingThrough() throws Exception {
		MaintenanceModeFilter filter = new MaintenanceModeFilter(false, "");
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/posts/");
		request.setServletPath("/posts/");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest()).isNotNull();
		assertThat(response.getStatus()).isEqualTo(200);
	}
}
