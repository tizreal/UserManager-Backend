package com.tca.UserManager.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.tca.UserManager.dto.LoginRequest;
import com.tca.UserManager.dto.SignupRequest;
import com.tca.UserManager.dto.UpdateProfileRequest;
import com.tca.UserManager.dto.UserResponse;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.exception.domain.EmailExistException;
import com.tca.UserManager.exception.domain.EmailNotVerifiedException;
import com.tca.UserManager.exception.domain.ForbiddenActionException;
import com.tca.UserManager.exception.domain.IncorrectPasswordException;
import com.tca.UserManager.exception.domain.InvalidTokenException;
import com.tca.UserManager.exception.domain.ResourceNotFoundException;
import com.tca.UserManager.exception.domain.UsernameExistException;
import com.tca.UserManager.repository.UserRepository;
import com.tca.UserManager.security.CurrentUser;
import com.tca.UserManager.security.JwtService;
import com.tca.UserManager.security.RateLimiter;
import com.tca.UserManager.security.TokenPurpose;

@Service
public class UserService {

	private static final Logger logger = LoggerFactory.getLogger(UserService.class);
	private static final String INVALID_VERIFICATION = "This verification link is invalid or has expired";

	private final UserRepository userRepository;
	private final EmailService emailService;
	private final PasswordEncoder passwordEncoder;
	private final AuthenticationManager authenticationManager;
	private final JwtService jwtService;
	private final DemoAccountPolicy demoPolicy;
	private final RateLimiter rateLimiter;

	public UserService(UserRepository userRepository, EmailService emailService, PasswordEncoder passwordEncoder,
			AuthenticationManager authenticationManager, JwtService jwtService, DemoAccountPolicy demoPolicy,
			RateLimiter rateLimiter) {
		this.userRepository = userRepository;
		this.emailService = emailService;
		this.passwordEncoder = passwordEncoder;
		this.authenticationManager = authenticationManager;
		this.jwtService = jwtService;
		this.demoPolicy = demoPolicy;
		this.rateLimiter = rateLimiter;
	}

	// ---- Signup, verification, login -------------------------------------------------

	/**
	 * Builds a brand-new entity from the allowed request fields only. The id, role,
	 * status, verification flag and token version can never come from the client.
	 */
	@Transactional
	public UserResponse signup(SignupRequest request) {
		String username = normalize(request.username());
		String email = normalize(request.email());
		if (username.startsWith(AccountLifecycleService.DELETED_USERNAME_PREFIX)) {
			throw new UsernameExistException("This username is reserved");
		}
		userRepository.findByUsername(username).ifPresent(u -> {
			throw new UsernameExistException("Username already exists: " + username);
		});
		userRepository.findByEmail(email).ifPresent(u -> {
			throw new EmailExistException("Email already exists: " + email);
		});

		Timestamp now = Timestamp.from(Instant.now());
		User user = new User();
		user.setUsername(username);
		user.setEmail(email);
		user.setPassword(passwordEncoder.encode(request.password()));
		user.setFirstName(trimToNull(request.firstName()));
		user.setLastName(trimToNull(request.lastName()));
		user.setPhoneNumber(trimToNull(request.phoneNumber()));
		user.setCountryCode(trimToNull(request.countryCode()));
		user.setEmailVerified(false);
		user.setRole(Role.USER);
		user.setCreatedAt(now);
		user.setUpdatedAt(now);

		// saveAndFlush so a concurrent duplicate fails here on the unique index (409),
		// before any email is sent.
		User saved = userRepository.saveAndFlush(user);
		emailService.sendVerificationEmail(saved, saved.getEmail(), jwtService.generateEmailVerificationToken(saved));
		logger.debug("Signed up user id={}", saved.getUserId());
		return UserResponse.from(saved);
	}

	/**
	 * Accepts only an EMAIL_VERIFICATION token, for an ACTIVE account, and only for the
	 * address the token was sent to: either the current email (signup) or the pending
	 * email (an address change, which is applied only now).
	 */
	@Transactional
	public void verifyEmail(String token) {
		DecodedJWT jwt;
		try {
			jwt = jwtService.verify(token, TokenPurpose.EMAIL_VERIFICATION);
		} catch (JWTVerificationException ex) {
			throw new InvalidTokenException(INVALID_VERIFICATION);
		}
		String tokenEmail = JwtService.email(jwt);
		User user = userRepository.findById(JwtService.userId(jwt))
				.filter(User::isActive)
				.filter(u -> Objects.equals(u.getUsername(), jwt.getSubject()))
				.orElseThrow(() -> new InvalidTokenException(INVALID_VERIFICATION));

		if (tokenEmail != null && tokenEmail.equals(user.getEmail())) {
			user.setEmailVerified(true);
		} else if (tokenEmail != null && tokenEmail.equals(user.getPendingEmail())) {
			// Someone may have registered the address since the change was requested.
			userRepository.findByEmail(tokenEmail).filter(u -> !u.getUserId().equals(user.getUserId()))
					.ifPresent(u -> {
						throw new EmailExistException("Email already in use: " + tokenEmail);
					});
			user.setEmail(tokenEmail);
			user.setPendingEmail(null);
			user.setEmailVerified(true);
		} else {
			throw new InvalidTokenException(INVALID_VERIFICATION);
		}
		user.setUpdatedAt(Timestamp.from(Instant.now()));
		userRepository.saveAndFlush(user);
	}

	public record LoginResult(UserResponse user, String accessToken) {
	}

	public LoginResult login(LoginRequest request) {
		String username = normalize(request.username());
		authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, request.password()));
		User user = userRepository.findByUsername(username).orElseThrow();
		if (!Boolean.TRUE.equals(user.getEmailVerified())) {
			throw new EmailNotVerifiedException("Email requires verification: " + user.getEmail());
		}
		return new LoginResult(UserResponse.from(user), jwtService.generateAccessToken(user));
	}

	public HttpHeaders tokenHeader(String accessToken) {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.AUTHORIZATION, accessToken);
		return headers;
	}

	// ---- Own account ------------------------------------------------------------------

	public UserResponse currentUser() {
		return UserResponse.from(CurrentUser.get());
	}

	public record UpdateResult(UserResponse user, String newAccessToken) {
	}

	/**
	 * Updates the caller's own profile. The demo account is read-only.
	 *
	 * Changing the password or the email address requires the current password.
	 * A new password signs out every other session and returns a fresh token for this one.
	 * A new email address is stored as pending and only replaces the current address once
	 * it is confirmed from the new inbox; until then login and reset keep using the
	 * current, verified address.
	 */
	@Transactional
	public UpdateResult updateProfile(UpdateProfileRequest request) {
		User current = userRepository.findById(CurrentUser.get().getUserId())
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
		if (demoPolicy.isDemo(current)) {
			throw new ForbiddenActionException("The demo account's profile and credentials can't be changed");
		}

		String requestedEmail = StringUtils.hasText(request.email()) ? normalize(request.email()) : null;
		boolean emailChange = requestedEmail != null && !requestedEmail.equals(current.getEmail());
		boolean passwordChange = StringUtils.hasText(request.password());

		if (emailChange || passwordChange) {
			rateLimiter.check(RateLimiter.Rule.CURRENT_PASSWORD_PER_USER, String.valueOf(current.getUserId()));
			if (!StringUtils.hasText(request.currentPassword())
					|| !passwordEncoder.matches(request.currentPassword(), current.getPassword())) {
				throw new IncorrectPasswordException();
			}
		}

		if (emailChange) {
			userRepository.findByEmail(requestedEmail)
					.filter(u -> !u.getUserId().equals(current.getUserId()))
					.ifPresent(u -> {
						throw new EmailExistException("Email already in use: " + requestedEmail);
					});
			current.setPendingEmail(requestedEmail);
		}
		if (StringUtils.hasText(request.firstName())) current.setFirstName(request.firstName().trim());
		if (StringUtils.hasText(request.lastName())) current.setLastName(request.lastName().trim());
		if (StringUtils.hasText(request.phoneNumber())) current.setPhoneNumber(request.phoneNumber().trim());
		if (StringUtils.hasText(request.countryCode())) current.setCountryCode(request.countryCode().trim());
		if (passwordChange) {
			current.setPassword(passwordEncoder.encode(request.password()));
			current.incrementTokenVersion();
		}
		current.setUpdatedAt(Timestamp.from(Instant.now()));
		User saved = userRepository.saveAndFlush(current);

		if (emailChange) {
			emailService.sendVerificationEmail(saved, requestedEmail,
					jwtService.generateEmailVerificationToken(saved, requestedEmail));
		}
		String newToken = passwordChange ? jwtService.generateAccessToken(saved) : null;
		return new UpdateResult(UserResponse.from(saved), newToken);
	}

	// ---- Admin -------------------------------------------------------------------------

	public List<UserResponse> listUsers() {
		return userRepository.findAll().stream().map(UserResponse::from).toList();
	}

	public UserResponse findByUsername(String username) {
		return userRepository.findByUsername(normalize(username)).map(UserResponse::from)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
	}

	// ---- Helpers -----------------------------------------------------------------------

	private static String normalize(String value) {
		return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
	}

	private static String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}
}
