package com.tca.UserManager.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tca.UserManager.dto.CreatePostRequest;
import com.tca.UserManager.dto.PostResponse;
import com.tca.UserManager.service.PostService;

import jakarta.validation.Valid;

/** All endpoints require an authenticated user; deletion is limited to the author or an admin. */
@RestController
@RequestMapping("/posts")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @PostMapping("/create")
    public PostResponse createPost(@Valid @RequestBody CreatePostRequest request) {
        return postService.createPost(request);
    }

    @GetMapping("/")
    public List<PostResponse> getAllPosts() {
        return postService.getAllPosts();
    }

    @PostMapping("/{id}/like")
    public PostResponse likePost(@PathVariable Long id) {
        return postService.likePost(id);
    }

    @PostMapping("/{id}/unlike")
    public PostResponse unlikePost(@PathVariable Long id) {
        return postService.unlikePost(id);
    }

    @DeleteMapping("/{id}")
    public void deletePost(@PathVariable Long id) {
        postService.deletePost(id);
    }
}
