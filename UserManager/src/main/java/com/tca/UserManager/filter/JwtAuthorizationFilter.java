package com.tca.UserManager.filter;

import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpMethod.OPTIONS;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.tca.UserManager.provider.ResourceProvider;
import com.tca.UserManager.security.JwtService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

@Component
public class JwtAuthorizationFilter extends OncePerRequestFilter {

	final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Autowired
	JwtService jwtService;
	@Autowired
	ResourceProvider provider;

	@Autowired
	@Qualifier("handlerExceptionResolver")
	HandlerExceptionResolver resolver;

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain filterChain)
			throws ServletException, IOException {

		logger.debug("JWT Filter running — URL: {}, Method: {}", req.getRequestURL(), req.getMethod());

		try {
			// Skip token check for CORS preflight requests (OPTIONS method)
			if (!req.getMethod().equalsIgnoreCase(OPTIONS.name())) {

				String header = req.getHeader(AUTHORIZATION);

				// Only process if Authorization header starts with our prefix (Bearer )
				if (isJwtPrefixValid(header)) {
					// Strip 'Bearer ' prefix (7 chars) to get the raw token
					String username = this.jwtService.getSubject(header.substring(7));

					// Set the authenticated user in Spring's security context
					SecurityContextHolder.getContext().setAuthentication(getAuthentication(username, req));

					logger.debug("User authorised: {}", username);
				}
			}
			filterChain.doFilter(req, res); // Continue to the next filter/controller
		} catch (JWTVerificationException ex) {
			logger.debug("Token invalid: {}", ex.getMessage());
			this.resolver.resolveException(req, res, null, ex);
		}
	}

	private Authentication getAuthentication(String username, HttpServletRequest req) {
		UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(username, null, null);
		auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
		return auth;
	}

	private boolean isJwtPrefixValid(String header) {
		logger.debug("Auth Header: {}", Optional.ofNullable(header).orElse("Not Present"));
		return Optional.ofNullable(header).filter(h -> h.startsWith(this.provider.getJwtPrefix())).isPresent();
	}
}

/*
 * OncePerRequestFilter guarantees this filter runs exactly once per request —
 * Spring's filter chain can sometimes call filters multiple times, so extending
 * this class prevents double-processing. The filter also skips OPTIONS
 * requests, which are browser CORS preflight checks that do not carry a token.
 * 
 */