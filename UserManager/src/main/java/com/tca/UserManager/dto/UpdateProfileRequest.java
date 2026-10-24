package com.tca.UserManager.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Profile fields a user may change on their own account. Null or blank means "leave unchanged". */
public record UpdateProfileRequest(
		@Size(max = 50) String firstName,
		@Size(max = 50) String lastName,
		@Size(max = 20) @Pattern(regexp = "^[0-9 +()-]*$", message = "must be a phone number") String phoneNumber,
		@Size(max = 5) String countryCode,
		@Email @Size(max = 254) String email,
		// Empty means "keep the current password"; otherwise 8-72 characters (BCrypt limit).
		@Pattern(regexp = "^$|^.{8,72}$", message = "must be 8 to 72 characters") String password,
		// Required when changing the password or the email address.
		@Size(max = 72) String currentPassword) {

	// Spring MVC logs parsed request bodies at DEBUG via toString(); never print the password.
	@Override
	public String toString() {
		return "UpdateProfileRequest[firstName=" + firstName + ", lastName=" + lastName
				+ ", passwordChanged=" + (password != null && !password.isEmpty()) + ", currentPassword=***]";
	}
}
