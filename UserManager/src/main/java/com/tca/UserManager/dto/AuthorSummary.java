package com.tca.UserManager.dto;

import com.tca.UserManager.entity.AccountStatus;
import com.tca.UserManager.entity.User;

/** Public identity of a post or comment author. No email, phone, or credentials. */
public record AuthorSummary(Integer userId, String username, String firstName, String lastName) {

	public static final String DELETED_LABEL = "Deleted user";

	/** Deleted accounts are shown generically: no id, no internal placeholder name. */
	public static AuthorSummary from(User u) {
		if (u.getStatus() == AccountStatus.DELETED) {
			return new AuthorSummary(null, DELETED_LABEL, null, null);
		}
		return new AuthorSummary(u.getUserId(), u.getUsername(), u.getFirstName(), u.getLastName());
	}
}
