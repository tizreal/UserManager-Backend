package com.tca.UserManager.filter;

import java.io.IOException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.repository.UserRepository;
import com.tca.UserManager.security.CustomUserDetails;
import com.tca.UserManager.security.JwtService;
import com.tca.UserManager.security.TokenPurpose;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates requests carrying an access token in the Authorization header.
 *
 * A token is accepted only if: the signature, issuer, audience and expiry are valid; its
 * purpose is ACCESS; the user still exists and is ACTIVE; the username still matches; the email is
 * verified; and its token version equals the user's current token_version (so tokens
 * issued before a password reset, or for a deleted account, stop working).
 *
 * An invalid token never throws here: the request simply continues unauthenticated, so
 * protected endpoints return 401 and public ones still work. Tokens are never logged.
 */
@Component
public class JwtAuthorizationFilter extends OncePerRequestFilter {

	private static final Logger logger = LoggerFactory.getLogger(JwtAuthorizationFilter.class);
	private static final String BEARER = "Bearer ";

	@Autowired
	JwtService jwtService;

	@Autowired
	UserRepository userRepository;

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
			throws ServletException, IOException {
		String header = req.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER)
				&& SecurityContextHolder.getContext().getAuthentication() == null) {
			authenticate(header.substring(BEARER.length()), req);
		}
		chain.doFilter(req, res);
	}

	private void authenticate(String token, HttpServletRequest req) {
		DecodedJWT jwt;
		try {
			jwt = jwtService.verify(token, TokenPurpose.ACCESS);
		} catch (JWTVerificationException ex) {
			logger.debug("Rejected access token: {}", ex.getClass().getSimpleName());
			return;
		}

		User user = userRepository.findById(JwtService.userId(jwt)).orElse(null);
		if (user == null
				|| !user.isActive()
				|| !Objects.equals(user.getUsername(), jwt.getSubject())
				|| !Boolean.TRUE.equals(user.getEmailVerified())
				|| !Objects.equals(user.getTokenVersion(), JwtService.tokenVersion(jwt))) {
			logger.debug("Rejected access token: account missing, changed, or session revoked");
			return;
		}

		CustomUserDetails details = new CustomUserDetails(user);
		UsernamePasswordAuthenticationToken auth =
				new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
		auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
		SecurityContextHolder.getContext().setAuthentication(auth);
	}
}
