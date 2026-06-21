package com.tca.UserManager.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.exception.domain.*;
import com.tca.UserManager.provider.ResourceProvider;
import com.tca.UserManager.repository.UserRepository;
import com.tca.UserManager.security.JwtService;

@Service
public class UserService {

	final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Autowired
	UserRepository userRepository;
	@Autowired
	EmailService emailService;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	AuthenticationManager authenticationManager;
	@Autowired
	JwtService jwtService;
	@Autowired
	ResourceProvider provider;

	// ── Utility: update a string field only if a value was provided ────
	private void updateValue(Supplier<String> getter, Consumer<String> setter) {
		Optional.ofNullable(getter.get()).map(String::trim).ifPresent(setter);
	}

	// ── Get all users ──────────────────────────────────────────────────
	public List<User> listUsers() {
		return this.userRepository.findAll();
	}

	// ── Find by username ───────────────────────────────────────────────
	public Optional<User> findByUsername(String username) {
		return this.userRepository.findByUsername(username);
	}

	// ── Delete by ID ───────────────────────────────────────────────────
	public void deleteUserById(Integer userId) {
		userRepository.deleteById(userId);
	}

	// ── SIGNUP ────────────────────────────────────────────────────────
	// Validates uniqueness, hashes password, sends verification email, saves
	public User signup(User user) {
		logger.debug("Signing up username={}", user.getUsername());

		user.setUsername(user.getUsername().toLowerCase());
		user.setEmail(user.getEmail().toLowerCase());

		this.validateUsernameAndEmail(user.getUsername(), user.getEmail());

		user.setEmailVerified(false);
		user.setPassword(this.passwordEncoder.encode(user.getPassword()));
		user.setCreatedAt(Timestamp.from(Instant.now()));

		this.emailService.sendVerificationEmail(user); // Async — runs in background
		logger.debug("Verification email queued for {}", user.getEmail());

		this.userRepository.save(user);
		return user;
	}

	// Validates that username and email are not already taken
	private void validateUsernameAndEmail(String username, String email) {
		this.userRepository.findByUsername(username).ifPresent(u -> {
			throw new UsernameExistException("Username already exists: " + u.getUsername());
		});
		this.userRepository.findByEmail(email).ifPresent(u -> {
			throw new EmailExistException("Email already exists: " + u.getEmail());
		});
	}

	// ── VERIFY EMAIL ──────────────────────────────────────────────────
	// Reads username from the JWT already validated by JwtAuthorizationFilter
	public void verifyEmail() {
		String username = SecurityContextHolder.getContext().getAuthentication().getName();

		User user = this.userRepository.findByUsername(username)
				.orElseThrow(() -> new UserNotFoundException("Username not found: " + username));

		user.setEmailVerified(true);
		this.userRepository.save(user);
	}

	// ── LOGIN ─────────────────────────────────────────────────────────
	// Authenticates with Spring Security, then checks email verification
	public User authenticate(User user) {
		this.authenticationManager
				.authenticate(new UsernamePasswordAuthenticationToken(user.getUsername(), user.getPassword()));

		return this.userRepository.findByUsername(user.getUsername()).map(UserService::isEmailVerified).get();

	}

	// Throws EmailNotVerifiedException if email_verified = false
	private static User isEmailVerified(User user) {
		if (user.getEmailVerified().equals(false)) {
			throw new EmailNotVerifiedException("Email requires verification: " + user.getEmail());
		}
		return user;
	}

	// ── GENERATE JWT HEADER ───────────────────────────────────────────
	// Creates the HTTP response header containing the token
	public HttpHeaders generateJwtHeader(String username) {
		HttpHeaders headers = new HttpHeaders();
		headers.add(AUTHORIZATION, this.jwtService.generateJwtToken(username, this.provider.getJwtExpiration()));
		return headers;
	}

	// ── SEND RESET PASSWORD EMAIL ─────────────────────────────────────
	public void sendResetPasswordEmail(String email) {
		Optional<User> opt = this.userRepository.findByEmail(email);
		if (opt.isPresent()) {
			this.emailService.sendResetPasswordEmail(opt.get());
		} else {
			logger.debug("Email not found for reset: {}", email);
		}
	}

	// ── RESET PASSWORD ────────────────────────────────────────────────
	// Validates the reset token, finds the user, saves new hashed password
	public void resetPassword(String token, String newPassword) {
		String username = jwtService.getSubject(token);
		User user = userRepository.findByUsername(username)
				.orElseThrow(() -> new UserNotFoundException("Username not found: " + username));

		user.setPassword(passwordEncoder.encode(newPassword));
		userRepository.save(user);
	}

	// ── GET CURRENT USER ──────────────────────────────────────────────
	public User getUser() {
		String username = SecurityContextHolder.getContext().getAuthentication().getName();
		return this.userRepository.findByUsername(username)
				.orElseThrow(() -> new UserNotFoundException("Username not found: " + username));
	}

	// ── UPDATE USER ───────────────────────────────────────────────────
	public User updateUser(User user) {
		String username = SecurityContextHolder.getContext().getAuthentication().getName();

		// Check new email is not taken by another user
		this.userRepository.findByEmail(user.getEmail()).filter(u -> !u.getUsername().equals(username)).ifPresent(u -> {
			throw new EmailExistException("Email already in use: " + u.getEmail());
		});

		return this.userRepository.findByUsername(username).map(current -> applyUpdates(user, current))
				.orElseThrow(() -> new UserNotFoundException("Username not found: " + username));
	}

	private User applyUpdates(User incoming, User current) {
		updateValue(incoming::getFirstName, current::setFirstName);
		updateValue(incoming::getLastName, current::setLastName);
		updateValue(incoming::getPhoneNumber, current::setPhoneNumber);
		updateValue(incoming::getEmail, current::setEmail);
		// Hash password only if a new one was provided
		Optional.ofNullable(incoming.getPassword()).filter(StringUtils::hasText).map(this.passwordEncoder::encode)
				.ifPresent(current::setPassword);
		return this.userRepository.save(current);
	}
}