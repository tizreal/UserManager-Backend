package com.tca.UserManager.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.tca.UserManager.entity.User;

/** The authenticated user, as loaded from the database by JwtAuthorizationFilter. */
public final class CurrentUser {

	private CurrentUser() {
	}

	public static User get() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof CustomUserDetails details) {
			return details.getUser();
		}
		throw new AuthenticationCredentialsNotFoundException("Not authenticated");
	}
}
