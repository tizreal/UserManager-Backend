package com.tca.UserManager.service;

import com.tca.UserManager.entity.Comment;
import com.tca.UserManager.entity.Post;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.repository.CommentRepository;
import com.tca.UserManager.repository.PostRepository;
import com.tca.UserManager.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CommentService {

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * Saves a comment.
     * Frontend sends: { "content": "...", "post": { "id": 5 } }
     * We load the full Post and User from DB before saving.
     */
    public Comment createComment(Comment comment) {
        String username = SecurityContextHolder.getContext()
                .getAuthentication()
                .getName();

        User author = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        Long postId = comment.getPost().getId();
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("Post not found: " + postId));

        comment.setUser(author);
        comment.setPost(post);
        return commentRepository.save(comment);
    }

    public List<Comment> getCommentsForPost(Long postId) {
        return commentRepository.findByPostIdOrderByCreatedAtAsc(postId);
    }
}