package com.tca.UserManager.dto;

import java.sql.Timestamp;

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
		Timestamp createdAt,
		Timestamp updatedAt) {

	public static UserResponse from(User u) {
		return new UserResponse(u.getUserId(), u.getUsername(), u.getFirstName(), u.getLastName(),
				u.getEmail(), u.getPhoneNumber(), u.getCountryCode(), u.getEmailVerified(),
				u.getRole(), u.getCreatedAt(), u.getUpdatedAt());
	}
}
