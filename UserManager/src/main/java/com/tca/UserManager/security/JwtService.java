package com.tca.UserManager.security;

import static com.auth0.jwt.algorithms.Algorithm.HMAC512;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.provider.ResourceProvider;

import jakarta.annotation.PostConstruct;

/**
 * Issues and verifies purpose-bound JWTs.
 *
 * Claims: sub = username, uid = user id, pur = purpose. Access tokens also carry
 * ver = the user's token_version (so sessions can be revoked) and role (for the UI
 * only; the server always re-reads the role from the database).
 */
@Component
public class JwtService {

	static final String CLAIM_PURPOSE = "pur";
	static final String CLAIM_USER_ID = "uid";
	static final String CLAIM_VERSION = "ver";
	static final String CLAIM_EMAIL = "email";
	static final String CLAIM_ROLE = "role";

	@Autowired
	ResourceProvider provider;

	/** HMAC-SHA512 needs a long random key; refuse to start with a weak or missing one. */
	@PostConstruct
	void checkSecretStrength() {
		String secret = provider.getJwtSecret();
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("JWT_SECRET must be at least 32 bytes (64+ random bytes recommended)");
		}
	}

	public String generateAccessToken(User user) {
		return JWT.create()
				.withIssuer(provider.getJwtIssuer())
				.withAudience(provider.getJwtAudience())
				.withIssuedAt(new Date())
				.withSubject(user.getUsername())
				.withClaim(CLAIM_USER_ID, user.getUserId())
				.withClaim(CLAIM_PURPOSE, TokenPurpose.ACCESS.claim())
				.withClaim(CLAIM_VERSION, user.getTokenVersion())
				.withClaim(CLAIM_ROLE, user.getRole().name())
				.withExpiresAt(new Date(System.currentTimeMillis() + provider.getJwtExpiration()))
				.sign(HMAC512(provider.getJwtSecret()));
	}

	/** Verification token for the account's current email address (signup). */
	public String generateEmailVerificationToken(User user) {
		return generateEmailVerificationToken(user, user.getEmail());
	}

	/**
	 * Bound to the address it was sent to (the current email at signup, or the pending
	 * address after an email change), so it cannot confirm any other address.
	 */
	public String generateEmailVerificationToken(User user, String recipientEmail) {
		return JWT.create()
				.withIssuer(provider.getJwtIssuer())
				.withAudience(provider.getJwtAudience())
				.withIssuedAt(new Date())
				.withSubject(user.getUsername())
				.withClaim(CLAIM_USER_ID, user.getUserId())
				.withClaim(CLAIM_PURPOSE, TokenPurpose.EMAIL_VERIFICATION.claim())
				.withClaim(CLAIM_EMAIL, recipientEmail)
				.withExpiresAt(new Date(System.currentTimeMillis() + provider.getClientVerifyExpiration()))
				.sign(HMAC512(provider.getJwtSecret()));
	}

	/**
	 * Verifies signature, issuer, audience, expiry, and that the token was issued for
	 * {@code expected}. Throws JWTVerificationException otherwise.
	 */
	public DecodedJWT verify(String token, TokenPurpose expected) throws JWTVerificationException {
		return JWT.require(HMAC512(provider.getJwtSecret()))
				.withIssuer(provider.getJwtIssuer())
				.withAudience(provider.getJwtAudience())
				.withClaim(CLAIM_PURPOSE, expected.claim())
				.withClaimPresence(CLAIM_USER_ID)
				.build()
				.verify(token);
	}

	public static Integer userId(DecodedJWT jwt) {
		return jwt.getClaim(CLAIM_USER_ID).asInt();
	}

	public static Integer tokenVersion(DecodedJWT jwt) {
		return jwt.getClaim(CLAIM_VERSION).asInt();
	}

	public static String email(DecodedJWT jwt) {
		return jwt.getClaim(CLAIM_EMAIL).asString();
	}
}
