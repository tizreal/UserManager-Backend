package com.tca.UserManager.service;

import com.tca.UserManager.entity.Post;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.repository.PostRepository;
import com.tca.UserManager.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PostService {

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * Saves a new post.
     * The author is resolved from the JWT via SecurityContextHolder —
     * the frontend only sends content, not a user ID.
     */
    public Post createPost(Post post) {
        String username = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();

        User author = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        post.setUser(author);
        return postRepository.save(post);
    }

    /** Returns every post, newest first. */
    public List<Post> getAllPosts() {
        return postRepository.findAllByOrderByCreatedAtDesc();
    }
    
    
    public Post likePost(Long id) {
        Post post = postRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Post not found: " + id));
        post.setLikesCount(post.getLikesCount() + 1);
        return postRepository.save(post);
    }

    public Post unlikePost(Long id) {
        Post post = postRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Post not found: " + id));
        post.setLikesCount(Math.max(0, post.getLikesCount() - 1));
        return postRepository.save(post);
    }

    public void deletePost(Long id) {
        postRepository.deleteById(id);
    }
    
    
}