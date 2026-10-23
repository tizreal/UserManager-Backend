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
import com.tca.UserManager.exception.domain.ConflictException;
import com.tca.UserManager.exception.domain.EmailExistException;
import com.tca.UserManager.exception.domain.EmailNotVerifiedException;
import com.tca.UserManager.exception.domain.ForbiddenActionException;
import com.tca.UserManager.exception.domain.InvalidTokenException;
import com.tca.UserManager.exception.domain.ResourceNotFoundException;
import com.tca.UserManager.exception.domain.UsernameExistException;
import com.tca.UserManager.repository.CommentRepository;
import com.tca.UserManager.repository.PostRepository;
import com.tca.UserManager.repository.UserRepository;
import com.tca.UserManager.security.CurrentUser;
import com.tca.UserManager.security.JwtService;
import com.tca.UserManager.security.TokenPurpose;

@Service
public class UserService {

	private static final Logger logger = LoggerFactory.getLogger(UserService.class);

	private final UserRepository userRepository;
	private final PostRepository postRepository;
	private final CommentRepository commentRepository;
	private final EmailService emailService;
	private final PasswordEncoder passwordEncoder;
	private final AuthenticationManager authenticationManager;
	private final JwtService jwtService;
	private final DemoAccountPolicy demoPolicy;

	public UserService(UserRepository userRepository, PostRepository postRepository,
			CommentRepository commentRepository, EmailService emailService, PasswordEncoder passwordEncoder,
			AuthenticationManager authenticationManager, JwtService jwtService, DemoAccountPolicy demoPolicy) {
		this.userRepository = userRepository;
		this.postRepository = postRepository;
		this.commentRepository = commentRepository;
		this.emailService = emailService;
		this.passwordEncoder = passwordEncoder;
		this.authenticationManager = authenticationManager;
		this.jwtService = jwtService;
		this.demoPolicy = demoPolicy;
	}

	// ---- Signup, verification, login -------------------------------------------------

	/**
	 * Builds a brand-new entity from the allowed request fields only. The id, role,
	 * verification flag and token version can never come from the client.
	 */
	@Transactional
	public UserResponse signup(SignupRequest request) {
		String username = normalize(request.username());
		String email = normalize(request.email());
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
		emailService.sendVerificationEmail(saved, jwtService.generateEmailVerificationToken(saved));
		logger.debug("Signed up user id={}", saved.getUserId());
		return UserResponse.from(saved);
	}

	/** Accepts only an EMAIL_VERIFICATION token, and only for the address it was sent to. */
	@Transactional
	public void verifyEmail(String token) {
		DecodedJWT jwt;
		try {
			jwt = jwtService.verify(token, TokenPurpose.EMAIL_VERIFICATION);
		} catch (JWTVerificationException ex) {
			throw new InvalidTokenException("This verification link is invalid or has expired");
		}
		User user = userRepository.findById(JwtService.userId(jwt))
				.filter(u -> Objects.equals(u.getUsername(), jwt.getSubject()))
				.filter(u -> Objects.equals(u.getEmail(), JwtService.email(jwt)))
				.orElseThrow(() -> new InvalidTokenException("This verification link is invalid or has expired"));
		user.setEmailVerified(true);
		userRepository.save(user);
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
	 * Updates the caller's own profile. The demo account is read-only. Changing the
	 * password signs out every other session and returns a fresh token for this one.
	 */
	@Transactional
	public UpdateResult updateProfile(UpdateProfileRequest request) {
		User current = userRepository.findById(CurrentUser.get().getUserId())
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
		if (demoPolicy.isDemo(current)) {
			throw new ForbiddenActionException("The demo account's profile and credentials can't be changed");
		}

		if (StringUtils.hasText(request.email())) {
			String email = normalize(request.email());
			userRepository.findByEmail(email)
					.filter(u -> !u.getUserId().equals(current.getUserId()))
					.ifPresent(u -> {
						throw new EmailExistException("Email already in use: " + email);
					});
			current.setEmail(email);
		}
		if (StringUtils.hasText(request.firstName())) current.setFirstName(request.firstName().trim());
		if (StringUtils.hasText(request.lastName())) current.setLastName(request.lastName().trim());
		if (StringUtils.hasText(request.phoneNumber())) current.setPhoneNumber(request.phoneNumber().trim());
		if (StringUtils.hasText(request.countryCode())) current.setCountryCode(request.countryCode().trim());

		String newToken = null;
		if (StringUtils.hasText(request.password())) {
			current.setPassword(passwordEncoder.encode(request.password()));
			current.incrementTokenVersion();
		}
		current.setUpdatedAt(Timestamp.from(Instant.now()));
		User saved = userRepository.saveAndFlush(current);
		if (StringUtils.hasText(request.password())) {
			newToken = jwtService.generateAccessToken(saved);
		}
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

	/**
	 * Hard-deletes an account that has no posts or comments. Accounts with content are
	 * refused (409) rather than cascade-deleted, pending a decision on soft deletion.
	 * Admins cannot delete themselves or the last remaining admin.
	 */
	@Transactional
	public void deleteUser(Integer userId) {
		User admin = CurrentUser.get();
		User target = userRepository.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
		if (target.getUserId().equals(admin.getUserId())) {
			throw new ConflictException("You can't delete your own account");
		}
		if (target.getRole() == Role.ADMIN && userRepository.countByRole(Role.ADMIN) <= 1) {
			throw new ConflictException("Can't delete the last remaining admin");
		}
		if (postRepository.existsByUserUserId(userId) || commentRepository.existsByUserUserId(userId)) {
			throw new ConflictException("This user has posts or comments; deleting accounts with content is not supported yet");
		}
		userRepository.delete(target);
		userRepository.flush();
	}

	// ---- Helpers -----------------------------------------------------------------------

	private static String normalize(String value) {
		return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
	}

	private static String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}
}
