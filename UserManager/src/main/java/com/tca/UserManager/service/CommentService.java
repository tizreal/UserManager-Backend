package com.tca.UserManager.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tca.UserManager.dto.CommentResponse;
import com.tca.UserManager.dto.CreateCommentRequest;
import com.tca.UserManager.entity.Comment;
import com.tca.UserManager.entity.Post;
import com.tca.UserManager.exception.domain.ResourceNotFoundException;
import com.tca.UserManager.repository.CommentRepository;
import com.tca.UserManager.repository.PostRepository;
import com.tca.UserManager.repository.UserRepository;
import com.tca.UserManager.security.CurrentUser;
import com.tca.UserManager.security.RateLimiter;

@Service
public class CommentService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final RateLimiter rateLimiter;

    public CommentService(CommentRepository commentRepository, PostRepository postRepository,
            UserRepository userRepository, RateLimiter rateLimiter) {
        this.commentRepository = commentRepository;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
    }

    /** The author is always the authenticated user; the post must exist. */
    @Transactional
    public CommentResponse createComment(CreateCommentRequest request) {
        rateLimiter.check(RateLimiter.Rule.COMMENT_PER_USER, String.valueOf(CurrentUser.get().getUserId()));
        Post post = postRepository.findById(request.post().id())
                .orElseThrow(() -> new ResourceNotFoundException("Post not found"));
        Comment comment = new Comment();
        comment.setContent(request.content().trim());
        comment.setPost(post);
        comment.setUser(userRepository.getReferenceById(CurrentUser.get().getUserId()));
        Comment saved = commentRepository.save(comment);
        return CommentResponse.from(commentRepository.findById(saved.getId()).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> getCommentsForPost(Long postId) {
        return commentRepository.findByPostIdOrderByCreatedAtAsc(postId).stream().map(CommentResponse::from).toList();
    }
}
