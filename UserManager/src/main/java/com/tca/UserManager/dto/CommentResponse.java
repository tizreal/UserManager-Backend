package com.tca.UserManager.dto;

import java.time.LocalDateTime;

import com.tca.UserManager.entity.Comment;

// Field name "user" is kept for compatibility with the existing frontend.
public record CommentResponse(Long id, String content, LocalDateTime createdAt, AuthorSummary user) {

	public static CommentResponse from(Comment c) {
		return new CommentResponse(c.getId(), c.getContent(), c.getCreatedAt(), AuthorSummary.from(c.getUser()));
	}
}
