package com.tca.UserManager.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String username, @NotBlank String password) {

	// Spring MVC logs parsed request bodies at DEBUG via toString(); never print the password.
	@Override
	public String toString() {
		return "LoginRequest[username=" + username + ", password=***]";
	}
}
