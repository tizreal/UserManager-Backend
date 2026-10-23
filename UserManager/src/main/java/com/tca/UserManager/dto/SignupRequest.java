package com.tca.UserManager.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Fields a new user may supply. Anything else in the request body (userId, role,
 * emailVerified, ...) is ignored, so privileged fields cannot be mass-assigned.
 */
public record SignupRequest(
		@NotBlank @Size(min = 3, max = 30)
		@Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "may contain letters, digits, '.', '_' and '-' only")
		String username,
		@NotBlank @Email @Size(max = 254) String email,
		// BCrypt only uses the first 72 bytes of a password.
		@NotBlank @Size(min = 8, max = 72) String password,
		@Size(max = 50) String firstName,
		@Size(max = 50) String lastName,
		@Size(max = 20) @Pattern(regexp = "^[0-9 +()-]*$", message = "must be a phone number") String phoneNumber,
		@Size(max = 5) String countryCode) {

	// Spring MVC logs parsed request bodies at DEBUG via toString(); never print the password.
	@Override
	public String toString() {
		return "SignupRequest[username=" + username + ", password=***]";
	}
}
