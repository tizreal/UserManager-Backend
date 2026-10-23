package com.tca.UserManager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Self-service account deletion must be confirmed with the current password. */
public record DeleteAccountRequest(@NotBlank @Size(max = 72) String currentPassword) {

	@Override
	public String toString() {
		return "DeleteAccountRequest[currentPassword=***]";
	}
}
