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

import com.tca.UserManager.dto.LoginRequest;
import com.tca.UserManager.dto.ResetPasswordRequest;
import com.tca.UserManager.dto.SignupRequest;
import com.tca.UserManager.dto.UpdateProfileRequest;
import com.tca.UserManager.dto.UserResponse;
import com.tca.UserManager.exception.domain.InvalidTokenException;
import com.tca.UserManager.service.PasswordResetService;
import com.tca.UserManager.service.UserService;

import jakarta.validation.Valid;

/** All endpoints are under /api/user (context path /api). Access rules: see WebSecurityConfig. */
@RestController
@RequestMapping("/user")
public class UserController {

	private final UserService userService;
	private final PasswordResetService passwordResetService;

	public UserController(UserService userService, PasswordResetService passwordResetService) {
		this.userService = userService;
		this.passwordResetService = passwordResetService;
	}

	// ---- Public ----

	@PostMapping("/signup")
	public UserResponse signup(@Valid @RequestBody SignupRequest request) {
		return userService.signup(request);
	}

	/** The verification link's token arrives in the Authorization header (existing frontend contract). */
	@GetMapping("/verify/email")
	public void verifyEmail(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		if (authorization == null || !authorization.startsWith("Bearer ")) {
			throw new InvalidTokenException("This verification link is invalid or has expired");
		}
		userService.verifyEmail(authorization.substring("Bearer ".length()));
	}

	@PostMapping("/login")
	public ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest request) {
		UserService.LoginResult result = userService.login(request);
		return new ResponseEntity<>(result.user(), userService.tokenHeader(result.accessToken()), OK);
	}

	/** Always 200, whether or not the email is registered. */
	@GetMapping("/reset/{email}")
	public void sendResetPasswordEmail(@PathVariable String email) {
		passwordResetService.requestReset(email);
	}

	@PostMapping("/reset")
	public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
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

	@PreAuthorize("hasRole('ADMIN')")
	@DeleteMapping("/{userId}")
	public String deleteUserById(@PathVariable Integer userId) {
		userService.deleteUser(userId);
		return "Deleted User";
	}
}
