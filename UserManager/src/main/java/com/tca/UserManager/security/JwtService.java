package com.tca.UserManager.security;

import static com.auth0.jwt.algorithms.Algorithm.HMAC512;

import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.provider.ResourceProvider;

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

	/** Bound to the email address it was sent to, so it cannot verify a later address. */
	public String generateEmailVerificationToken(User user) {
		return JWT.create()
				.withIssuer(provider.getJwtIssuer())
				.withAudience(provider.getJwtAudience())
				.withIssuedAt(new Date())
				.withSubject(user.getUsername())
				.withClaim(CLAIM_USER_ID, user.getUserId())
				.withClaim(CLAIM_PURPOSE, TokenPurpose.EMAIL_VERIFICATION.claim())
				.withClaim(CLAIM_EMAIL, user.getEmail())
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
