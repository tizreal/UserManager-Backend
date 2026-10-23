package com.tca.UserManager.dto;

import com.tca.UserManager.entity.User;

/** Public identity of a post or comment author. No email, phone, or credentials. */
public record AuthorSummary(Integer userId, String username, String firstName, String lastName) {

	public static AuthorSummary from(User u) {
		return new AuthorSummary(u.getUserId(), u.getUsername(), u.getFirstName(), u.getLastName());
	}
}
