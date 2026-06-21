package com.tca.UserManager.filter;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Handles 401 Unauthorized when an unauthenticated request hits a protected endpoint.

// Delegates to the global exception handler so the response is consistent JSON. 
@Component
public class CustomAuthEntryPoint implements AuthenticationEntryPoint {

	@Autowired
	@Qualifier("handlerExceptionResolver")
	HandlerExceptionResolver resolver;

	@Override
	public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex)
			throws IOException {
		// Route the exception through Spring's normal exception handling pipeline
		this.resolver.resolveException(req, res, null, ex);
	}
}

/**
 * Filters are the first code that runs when any HTTP request arrives.
 * JwtAuthorizationFilter checks for a valid token on every protected request.
 * CustomAuthEntryPoint handles what happens when authentication fails —
 * returning a clean JSON error instead of a Spring redirect.
 */