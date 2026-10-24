package com.tca.UserManager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
		@NotBlank String token,
		@NotBlank @Size(min = 8, max = 72) String password) {

	// Spring MVC logs parsed request bodies at DEBUG via toString(); never print secrets.
	@Override
	public String toString() {
		return "ResetPasswordRequest[token=***, password=***]";
	}
}
