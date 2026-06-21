package com.tca.UserManager.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tca.UserManager.provider.ResourceProvider;
import static com.auth0.jwt.algorithms.Algorithm.HMAC512;
import java.util.Date;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;

@Component
public class JwtService {

	@Autowired
	ResourceProvider provider; // Injects JWT config from config.yml

	final Logger logger = LoggerFactory.getLogger(this.getClass());

	/**
	 * Creates a signed JWT for a given username with an expiration time. Called
	 * after successful login and after email verification.
	 */
	public String generateJwtToken(String username, long expiration) {
		return JWT.create().withIssuer(this.provider.getJwtIssuer()) // Who issued the token
				.withAudience(this.provider.getJwtAudience()) // Who can use the token
				.withIssuedAt(new Date()) // When created
				.withSubject(username) // The user this token is for
				.withExpiresAt(new Date(System.currentTimeMillis() + expiration))
				.sign(HMAC512(this.provider.getJwtSecret())); // Cryptographic signature

	}

	/**
	 * Verifies a token's signature and returns the decoded JWT. Throws
	 * JWTVerificationException if the token is invalid or expired.
	 */
	public DecodedJWT verifyJwtToken(String token) {
		return JWT.require(HMAC512(this.provider.getJwtSecret())).withIssuer(this.provider.getJwtIssuer()).build()
				.verify(token);
	}

	/**
	 * Extracts the username (subject) from a token without a full verification
	 * step. Used by JwtAuthorizationFilter on every protected request.
	 */
	public String getSubject(String token) {
		return JWT.require(HMAC512(this.provider.getJwtSecret())).withIssuer(this.provider.getJwtIssuer()).build()
				.verify(token).getSubject();
	}
}