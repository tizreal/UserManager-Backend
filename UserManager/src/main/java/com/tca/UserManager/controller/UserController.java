package com.tca.UserManager.controller;

import static org.springframework.http.HttpStatus.OK;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tca.UserManager.dto.DeleteAccountRequest;
import com.tca.UserManager.dto.LoginRequest;
import com.tca.UserManager.dto.ResetPasswordRequest;
import com.tca.UserManager.dto.SignupRequest;
import com.tca.UserManager.dto.UpdateProfileRequest;
import com.tca.UserManager.dto.UserResponse;
import com.tca.UserManager.exception.domain.InvalidTokenException;
import com.tca.UserManager.security.RateLimiter;
import com.tca.UserManager.security.RateLimiter.Rule;
import com.tca.UserManager.service.AccountLifecycleService;
import com.tca.UserManager.service.PasswordResetService;
import com.tca.UserManager.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** All endpoints are under /api/user (context path /api). Access rules: see WebSecurityConfig. */
@RestController
@RequestMapping("/user")
public class UserController {

	private final UserService userService;
	private final PasswordResetService passwordResetService;
	private final AccountLifecycleService accountLifecycleService;
	private final RateLimiter rateLimiter;

	public UserController(UserService userService, PasswordResetService passwordResetService,
			AccountLifecycleService accountLifecycleService, RateLimiter rateLimiter) {
		this.userService = userService;
		this.passwordResetService = passwordResetService;
		this.accountLifecycleService = accountLifecycleService;
		this.rateLimiter = rateLimiter;
	}

	// Client address as seen by Tomcat; behind Render's proxy this relies on
	// server.forward-headers-strategy=native (see application.properties).
	private static String clientIp(HttpServletRequest request) {
		return request.getRemoteAddr();
	}

	// ---- Public ----

	@PostMapping("/signup")
	public UserResponse signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
		rateLimiter.check(Rule.SIGNUP_PER_IP, clientIp(http));
		return userService.signup(request);
	}

	/** The verification link's token arrives in the Authorization header (existing frontend contract). */
	@GetMapping("/verify/email")
	public void verifyEmail(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			HttpServletRequest http) {
		rateLimiter.check(Rule.VERIFY_EMAIL_PER_IP, clientIp(http));
		if (authorization == null || !authorization.startsWith("Bearer ")) {
			throw new InvalidTokenException("This verification link is invalid or has expired");
		}
		userService.verifyEmail(authorization.substring("Bearer ".length()));
	}

	@PostMapping("/login")
	public ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		rateLimiter.check(Rule.LOGIN_PER_IP, clientIp(http));
		rateLimiter.check(Rule.LOGIN_PER_ACCOUNT, request.username().trim());
		UserService.LoginResult result = userService.login(request);
		return new ResponseEntity<>(result.user(), userService.tokenHeader(result.accessToken()), OK);
	}

	/** Always 200, whether or not the email is registered. */
	@GetMapping("/reset/{email}")
	public void sendResetPasswordEmail(@PathVariable String email, HttpServletRequest http) {
		rateLimiter.check(Rule.RESET_REQUEST_PER_IP, clientIp(http));
		rateLimiter.check(Rule.RESET_REQUEST_PER_ACCOUNT, email.trim());
		passwordResetService.requestReset(email);
	}

	@PostMapping("/reset")
	public void resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
		rateLimiter.check(Rule.RESET_SUBMIT_PER_IP, clientIp(http));
		passwordResetService.resetPassword(request.token(), request.password());
	}

	// ---- Authenticated: own account ----

	@GetMapping("/get")
	public UserResponse getUser() {
		return userService.currentUser();
	}

	/** After a password change the response carries a fresh token; older sessions are signed out. */
	@PostMapping("/update")
	public ResponseEntity<UserResponse> updateUser(@Valid @RequestBody UpdateProfileRequest request) {
		UserService.UpdateResult result = userService.updateProfile(request);
		HttpHeaders headers = result.newAccessToken() == null ? new HttpHeaders()
				: userService.tokenHeader(result.newAccessToken());
		return new ResponseEntity<>(result.user(), headers, OK);
	}

	/** Irreversibly anonymizes the caller's own account (see AccountLifecycleService). */
	@PostMapping("/delete-account")
	public void deleteOwnAccount(@Valid @RequestBody DeleteAccountRequest request) {
		accountLifecycleService.deleteOwnAccount(request.currentPassword());
	}

	// ---- Admin only ----

	@PreAuthorize("hasRole('ADMIN')")
	@GetMapping("/")
	public List<UserResponse> listUsers() {
		return userService.listUsers();
	}

	@PreAuthorize("hasRole('ADMIN')")
	@GetMapping("/{username}")
	public UserResponse findByUsername(@PathVariable String username) {
		return userService.findByUsername(username);
	}

	/** Irreversibly anonymizes another account; content is kept under "deleted-user-<id>". */
	@PreAuthorize("hasRole('ADMIN')")
	@DeleteMapping("/{userId}")
	public String deleteUserById(@PathVariable Integer userId) {
		accountLifecycleService.deleteByAdmin(userId);
		return "Deleted User";
	}

	@PreAuthorize("hasRole('ADMIN')")
	@PostMapping("/{userId}/deactivate")
	public UserResponse deactivate(@PathVariable Integer userId) {
		return accountLifecycleService.deactivate(userId);
	}

	@PreAuthorize("hasRole('ADMIN')")
	@PostMapping("/{userId}/reactivate")
	public UserResponse reactivate(@PathVariable Integer userId) {
		return accountLifecycleService.reactivate(userId);
	}
}
