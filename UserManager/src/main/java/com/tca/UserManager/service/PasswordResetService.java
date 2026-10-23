package com.tca.UserManager.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tca.UserManager.entity.PasswordResetToken;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.exception.domain.InvalidTokenException;
import com.tca.UserManager.provider.ResourceProvider;
import com.tca.UserManager.repository.PasswordResetTokenRepository;
import com.tca.UserManager.repository.UserRepository;

/**
 * Single-use password reset.
 *
 * Issue: a 256-bit random token is emailed; only its SHA-256 hash is stored, with a
 * 10-minute expiry. Issuing a new token retires any older unused ones for that user.
 *
 * Consume: one conditional UPDATE marks the token used only if it is still unused and
 * unexpired. Under concurrent requests PostgreSQL serializes the UPDATEs on the row,
 * so exactly one request succeeds. The password change, the token-version bump (which
 * signs out every existing session) and the token consumption commit together.
 */
@Service
public class PasswordResetService {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String INVALID = "This reset link is invalid, expired, or has already been used";

	private final PasswordResetTokenRepository tokenRepository;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final EmailService emailService;
	private final DemoAccountPolicy demoPolicy;
	private final ResourceProvider provider;
	private final Clock clock;

	public PasswordResetService(PasswordResetTokenRepository tokenRepository, UserRepository userRepository,
			PasswordEncoder passwordEncoder, EmailService emailService, DemoAccountPolicy demoPolicy,
			ResourceProvider provider) {
		this.tokenRepository = tokenRepository;
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.emailService = emailService;
		this.demoPolicy = demoPolicy;
		this.provider = provider;
		this.clock = Clock.systemUTC();
	}

	/**
	 * Always completes normally, whether or not the email exists, so the endpoint
	 * cannot be used to discover registered addresses. The demo account never gets
	 * reset links.
	 */
	@Transactional
	public void requestReset(String email) {
		String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
		User user = userRepository.findByEmail(normalized).orElse(null);
		if (user == null || demoPolicy.isDemo(user)) {
			return;
		}

		Instant now = clock.instant();
		tokenRepository.invalidateAllForUser(user.getUserId(), now);

		String rawToken = newRawToken();
		Instant expiresAt = now.plusMillis(provider.getClientResetExpiration());
		tokenRepository.save(new PasswordResetToken(user.getUserId(), sha256(rawToken), now, expiresAt));

		emailService.sendResetPasswordEmail(user, rawToken);
	}

	@Transactional
	public void resetPassword(String rawToken, String newPassword) {
		String hash = sha256(rawToken == null ? "" : rawToken);
		Instant now = clock.instant();

		if (tokenRepository.consume(hash, now) != 1) {
			throw new InvalidTokenException(INVALID);
		}

		PasswordResetToken token = tokenRepository.findByTokenHash(hash)
				.orElseThrow(() -> new InvalidTokenException(INVALID));
		User user = userRepository.findById(token.getUserId())
				.orElseThrow(() -> new InvalidTokenException(INVALID));
		if (demoPolicy.isDemo(user)) {
			throw new InvalidTokenException(INVALID);
		}

		user.setPassword(passwordEncoder.encode(newPassword));
		user.setUpdatedAt(Timestamp.from(now));
		user.incrementTokenVersion(); // sign out all existing sessions
		userRepository.save(user);

		tokenRepository.invalidateAllForUser(user.getUserId(), now);
	}

	private static String newRawToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
