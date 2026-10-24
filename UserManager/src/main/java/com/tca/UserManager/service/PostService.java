package com.tca.UserManager.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tca.UserManager.dto.CreatePostRequest;
import com.tca.UserManager.dto.PostResponse;
import com.tca.UserManager.entity.Post;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.exception.domain.ForbiddenActionException;
import com.tca.UserManager.exception.domain.ResourceNotFoundException;
import com.tca.UserManager.repository.PostRepository;
import com.tca.UserManager.repository.UserRepository;
import com.tca.UserManager.security.CurrentUser;
import com.tca.UserManager.security.RateLimiter;

@Service
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final RateLimiter rateLimiter;

    public PostService(PostRepository postRepository, UserRepository userRepository, RateLimiter rateLimiter) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
    }

    /** The author is always the authenticated user, never a value from the request. */
    @Transactional
    public PostResponse createPost(CreatePostRequest request) {
        rateLimiter.check(RateLimiter.Rule.POST_PER_USER, String.valueOf(CurrentUser.get().getUserId()));
        User author = userRepository.getReferenceById(CurrentUser.get().getUserId());
        Post post = new Post();
        post.setContent(request.content().trim());
        post.setImage(request.image());
        post.setUser(author);
        return PostResponse.from(postRepository.save(post));
    }

    @Transactional(readOnly = true)
    public List<PostResponse> getAllPosts() {
        return postRepository.findAllByOrderByCreatedAtDesc().stream().map(PostResponse::from).toList();
    }

    @Transactional
    public PostResponse likePost(Long id) {
        rateLimiter.check(RateLimiter.Rule.LIKE_PER_USER, String.valueOf(CurrentUser.get().getUserId()));
        Post post = findPost(id);
        post.setLikesCount(post.getLikesCount() + 1);
        return PostResponse.from(postRepository.save(post));
    }

    @Transactional
    public PostResponse unlikePost(Long id) {
        rateLimiter.check(RateLimiter.Rule.LIKE_PER_USER, String.valueOf(CurrentUser.get().getUserId()));
        Post post = findPost(id);
        post.setLikesCount(Math.max(0, post.getLikesCount() - 1));
        return PostResponse.from(postRepository.save(post));
    }

    /** Only the post's author or an admin may delete it. */
    @Transactional
    public void deletePost(Long id) {
        Post post = findPost(id);
        User caller = CurrentUser.get();
        boolean isOwner = post.getUser().getUserId().equals(caller.getUserId());
        if (!isOwner && caller.getRole() != Role.ADMIN) {
            throw new ForbiddenActionException("You can only delete your own posts");
        }
        postRepository.delete(post);
    }

    private Post findPost(Long id) {
        return postRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Post not found"));
    }
}
