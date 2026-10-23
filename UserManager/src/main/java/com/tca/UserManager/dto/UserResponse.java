package com.tca.UserManager.dto;

import java.sql.Timestamp;

import com.tca.UserManager.entity.AccountStatus;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;

/**
 * A user's own account details (or any account, for admins). Never includes the
 * password hash or token version.
 */
public record UserResponse(
		Integer userId,
		String username,
		String firstName,
		String lastName,
		String email,
		String phoneNumber,
		String countryCode,
		Boolean emailVerified,
		Role role,
		AccountStatus status,
		// Address awaiting confirmation after an email change (null if none).
		String pendingEmail,
		Timestamp createdAt,
		Timestamp updatedAt) {

	public static UserResponse from(User u) {
		return new UserResponse(u.getUserId(), u.getUsername(), u.getFirstName(), u.getLastName(),
				u.getEmail(), u.getPhoneNumber(), u.getCountryCode(), u.getEmailVerified(),
				u.getRole(), u.getStatus(), u.getPendingEmail(), u.getCreatedAt(), u.getUpdatedAt());
	}
}
