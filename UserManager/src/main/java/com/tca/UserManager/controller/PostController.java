package com.tca.UserManager.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.tca.UserManager.entity.Post;
import com.tca.UserManager.service.PostService;

@RestController
@RequestMapping("/posts")
public class PostController {
	
	@Autowired
    private PostService postService;
	
	// PostController.java (confirm this exists in your backend) 
	@PostMapping("/create") 
	public Post createPost(@RequestBody Post post) { 
	    return this.postService.createPost(post); 
	} 
	
	  // GET /api/posts/
    @GetMapping("/")
    public List<Post> getAllPosts() {
        return postService.getAllPosts();
    }
    
 // POST /posts/{id}/like
    @PostMapping("/{id}/like")
    public Post likePost(@PathVariable Long id) {
        return postService.likePost(id);
    }

    // POST /posts/{id}/unlike
    @PostMapping("/{id}/unlike")
    public Post unlikePost(@PathVariable Long id) {
        return postService.unlikePost(id);
    }

    // DELETE /posts/{id}
    @DeleteMapping("/{id}")
    public void deletePost(@PathVariable Long id) {
        postService.deletePost(id);
    }
	 
	// Ensure /posts/create is in your excluded or authenticated URLs

}
