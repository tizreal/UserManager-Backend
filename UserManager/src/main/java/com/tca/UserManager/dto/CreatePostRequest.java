package com.tca.UserManager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The author always comes from the authenticated user, never from the request. */
public record CreatePostRequest(
		@NotBlank @Size(max = 5000) String content,
		// Base64 data URL from the frontend; capped to keep requests bounded.
		@Size(max = 8_000_000) String image) {
}
