package com.tca.UserManager.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Maintenance mode (MAINTENANCE_MODE=true): every endpoint except /health returns 503,
 * unless the request carries the X-Maintenance-Key header matching
 * MAINTENANCE_BYPASS_KEY. Used to deploy, migrate and smoke-test a release with public
 * access closed, and as the safe "rollback" state that does not reopen an older,
 * vulnerable build. Runs before Spring Security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MaintenanceModeFilter extends OncePerRequestFilter {

	static final String BYPASS_HEADER = "X-Maintenance-Key";

	private final boolean enabled;
	private final byte[] bypassKey;

	public MaintenanceModeFilter(@Value("${app.maintenance.enabled:false}") boolean enabled,
			@Value("${app.maintenance.bypass-key:}") String bypassKey) {
		this.enabled = enabled;
		this.bypassKey = bypassKey == null ? new byte[0] : bypassKey.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// Path relative to the /api context path, independent of servlet mapping details.
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !enabled || "/health".equals(path);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
			throws ServletException, IOException {
		String supplied = req.getHeader(BYPASS_HEADER);
		// An empty configured key never matches, so maintenance cannot be bypassed by accident.
		if (bypassKey.length > 0 && supplied != null
				&& MessageDigest.isEqual(bypassKey, supplied.getBytes(StandardCharsets.UTF_8))) {
			chain.doFilter(req, res);
			return;
		}
		res.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
		res.setHeader("Retry-After", "600");
		res.setContentType(MediaType.APPLICATION_JSON_VALUE);
		res.getWriter().write("{\"httpStatusCode\":503,\"httpStatus\":\"SERVICE_UNAVAILABLE\","
				+ "\"reason\":\"SERVICE UNAVAILABLE\",\"message\":\"Down for maintenance. Please try again later.\"}");
	}
}
