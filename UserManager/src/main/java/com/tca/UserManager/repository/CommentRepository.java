package com.tca.UserManager.repository;

import com.tca.UserManager.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    // All comments for a post, oldest-first
    List<Comment> findByPostIdOrderByCreatedAtAsc(Long postId);
}