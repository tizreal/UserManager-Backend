package com.tca.UserManager.service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tca.UserManager.dto.UserResponse;
import com.tca.UserManager.entity.AccountStatus;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.exception.domain.ConflictException;
import com.tca.UserManager.exception.domain.ForbiddenActionException;
import com.tca.UserManager.exception.domain.IncorrectPasswordException;
import com.tca.UserManager.exception.domain.ResourceNotFoundException;
import com.tca.UserManager.repository.CommentRepository;
import com.tca.UserManager.repository.PasswordResetTokenRepository;
import com.tca.UserManager.repository.PostRepository;
import com.tca.UserManager.repository.UserRepository;
import com.tca.UserManager.security.CurrentUser;
import com.tca.UserManager.security.RateLimiter;

/**
 * Account lifecycle policy.
 *
 * Deactivate (admin, reversible): status DISABLED; every session is revoked; outstanding
 * reset links are retired; content and attribution are untouched. Reactivate restores
 * ACTIVE (old sessions stay revoked).
 *
 * Delete (admin, or the user with their current password; irreversible): personal data
 * on the account is anonymized in place: username becomes "deleted-user-<id>"; email,
 * pending email, names, phone and country code are cleared; the password is replaced
 * with an unusable random hash. Sessions are revoked and reset tokens deleted. The user's
 * posts and comments stay in place, so threads and other people's replies keep their
 * structure, but their text is replaced with "[deleted]" and images are removed, because
 * free text and photos can contain personal data. Publicly the author appears as
 * "Deleted user" (see AuthorSummary).
 *
 * Neither operation may leave the system without an ACTIVE admin, and the shared demo
 * account cannot be deleted (it can be deactivated by an admin if it is being abused).
 */
@Service
public class AccountLifecycleService {

	public static final String DELETED_USERNAME_PREFIX = "deleted-user-";
	public static final String REDACTED_CONTENT = "[deleted]";

	private static final Logger logger = LoggerFactory.getLogger(AccountLifecycleService.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserRepository userRepository;
	private final PostRepository postRepository;
	private final CommentRepository commentRepository;
	private final PasswordResetTokenRepository tokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final DemoAccountPolicy demoPolicy;
	private final RateLimiter rateLimiter;

	public AccountLifecycleService(UserRepository userRepository, PostRepository postRepository,
			CommentRepository commentRepository, PasswordResetTokenRepository tokenRepository,
			PasswordEncoder passwordEncoder, DemoAccountPolicy demoPolicy, RateLimiter rateLimiter) {
		this.userRepository = userRepository;
		this.postRepository = postRepository;
		this.commentRepository = commentRepository;
		this.tokenRepository = tokenRepository;
		this.passwordEncoder = passwordEncoder;
		this.demoPolicy = demoPolicy;
		this.rateLimiter = rateLimiter;
	}

	// ---- Deactivation (admin) ----

	@Transactional
	public UserResponse deactivate(Integer userId) {
		User target = find(userId);
		if (target.getUserId().equals(CurrentUser.get().getUserId())) {
			throw new ConflictException("You can't deactivate your own account");
		}
		if (target.getStatus() == AccountStatus.DELETED) {
			throw new ConflictException("Deleted accounts can't be deactivated");
		}
		requireAnotherActiveAdmin(target);
		if (target.getStatus() == AccountStatus.ACTIVE) {
			target.setStatus(AccountStatus.DISABLED);
			target.incrementTokenVersion();
			target.setUpdatedAt(Timestamp.from(Instant.now()));
			tokenRepository.invalidateAllForUser(target.getUserId(), Instant.now());
			userRepository.saveAndFlush(target);
			logger.info("Deactivated user id={}", target.getUserId());
		}
		return UserResponse.from(target);
	}

	@Transactional
	public UserResponse reactivate(Integer userId) {
		User target = find(userId);
		if (target.getStatus() == AccountStatus.DELETED) {
			throw new ConflictException("Deleted accounts can't be reactivated");
		}
		if (target.getStatus() == AccountStatus.DISABLED) {
			target.setStatus(AccountStatus.ACTIVE);
			target.setUpdatedAt(Timestamp.from(Instant.now()));
			userRepository.saveAndFlush(target);
			logger.info("Reactivated user id={}", target.getUserId());
		}
		return UserResponse.from(target);
	}

	// ---- Deletion ----

	/** Admin deletes another account. Admins delete their own account via deleteOwnAccount. */
	@Transactional
	public void deleteByAdmin(Integer userId) {
		User target = find(userId);
		if (target.getUserId().equals(CurrentUser.get().getUserId())) {
			throw new ConflictException("Use account settings to delete your own account");
		}
		anonymize(target);
	}

	/** The signed-in user deletes their own account, confirming with their current password. */
	@Transactional
	public void deleteOwnAccount(String currentPassword) {
		User self = find(CurrentUser.get().getUserId());
		if (demoPolicy.isDemo(self)) {
			throw new ForbiddenActionException("The demo account can't be deleted");
		}
		rateLimiter.check(RateLimiter.Rule.CURRENT_PASSWORD_PER_USER, String.valueOf(self.getUserId()));
		if (!passwordEncoder.matches(currentPassword, self.getPassword())) {
			throw new IncorrectPasswordException();
		}
		anonymize(self);
	}

	private void anonymize(User user) {
		if (user.getStatus() == AccountStatus.DELETED) {
			throw new ConflictException("This account has already been deleted");
		}
		if (demoPolicy.isDemo(user)) {
			throw new ConflictException("The demo account can't be deleted");
		}
		requireAnotherActiveAdmin(user);

		Instant now = Instant.now();
		Integer id = user.getUserId();
		user.setUsername(DELETED_USERNAME_PREFIX + id);
		user.setEmail(null);
		user.setPendingEmail(null);
		user.setFirstName(null);
		user.setLastName(null);
		user.setPhoneNumber(null);
		user.setCountryCode(null);
		user.setPassword(passwordEncoder.encode(randomSecret()));
		user.setEmailVerified(false);
		user.setRole(Role.USER);
		user.setStatus(AccountStatus.DELETED);
		user.setDeletedAt(now);
		user.setUpdatedAt(Timestamp.from(now));
		user.incrementTokenVersion();
		userRepository.saveAndFlush(user);

		tokenRepository.deleteAllForUser(id);
		postRepository.redactForUser(id, REDACTED_CONTENT);
		commentRepository.redactForUser(id, REDACTED_CONTENT);
		logger.info("Deleted (anonymized) user id={}", id);
	}

	// ---- Helpers ----

	/** Refuses to disable or delete the only remaining ACTIVE admin. */
	private void requireAnotherActiveAdmin(User target) {
		if (target.getRole() == Role.ADMIN && target.getStatus() == AccountStatus.ACTIVE
				&& userRepository.countByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE) <= 1) {
			throw new ConflictException("Can't remove the last active admin");
		}
	}

	private User find(Integer userId) {
		return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
	}

	private static String randomSecret() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
