package com.tca.UserManager.repository;

import com.tca.UserManager.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    // All comments for a post, oldest-first
    List<Comment> findByPostIdOrderByCreatedAtAsc(Long postId);

    boolean existsByUserUserId(Integer userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Comment c WHERE c.user.userId = :userId")
    int deleteByUserId(@Param("userId") Integer userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Comment c WHERE c.user.userId = :userId AND c.createdAt < :cutoff")
    int deleteByUserIdCreatedBefore(@Param("userId") Integer userId, @Param("cutoff") LocalDateTime cutoff);

    boolean existsByPostIdAndUserUserIdNot(Long postId, Integer userId);

    // Account deletion: the comment stays in the thread, its text is removed.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Comment c SET c.content = :placeholder WHERE c.user.userId = :userId")
    int redactForUser(@Param("userId") Integer userId, @Param("placeholder") String placeholder);
}
