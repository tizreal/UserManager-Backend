package com.tca.UserManager.repository;

import com.tca.UserManager.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    // All comments for a post, oldest-first
    List<Comment> findByPostIdOrderByCreatedAtAsc(Long postId);

    boolean existsByUserUserId(Integer userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Comment c WHERE c.user.userId = :userId")
    int deleteByUserId(@Param("userId") Integer userId);
}
