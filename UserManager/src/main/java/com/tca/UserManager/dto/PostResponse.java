package com.tca.UserManager.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.tca.UserManager.entity.Post;

// Field names ("user", "comments", "likesCount") match what the existing frontend reads.
public record PostResponse(
		Long id,
		String content,
		String image,
		Integer likesCount,
		LocalDateTime createdAt,
		AuthorSummary user,
		List<CommentResponse> comments) {

	public static PostResponse from(Post p) {
		return new PostResponse(p.getId(), p.getContent(), p.getImage(), p.getLikesCount(), p.getCreatedAt(),
				AuthorSummary.from(p.getUser()),
				p.getComments().stream().map(CommentResponse::from).toList());
	}
}
