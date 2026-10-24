package com.tca.UserManager.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Shape matches the frontend's { content, post: { id } } payload. */
public record CreateCommentRequest(
		@NotBlank @Size(max = 2000) String content,
		@NotNull @Valid PostRef post) {

	public record PostRef(@NotNull Long id) {
	}
}
