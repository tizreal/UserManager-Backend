package com.tca.UserManager.controller;

import com.tca.UserManager.entity.Comment;
import com.tca.UserManager.service.CommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;



@RestController
@RequestMapping("/comments")
public class CommentController {

    @Autowired
    private CommentService commentService;

    // POST /api/comments/create
    // Body: { "content": "Nice!", "post": { "id": 5 } }
    @PostMapping("/create")
    public Comment createComment(@RequestBody Comment comment) {
        return commentService.createComment(comment);
    }

    // GET /api/comments/{postId}
    @GetMapping("/{postId}")
    public List<Comment> getComments(@PathVariable Long postId) {
        return commentService.getCommentsForPost(postId);
    }
}