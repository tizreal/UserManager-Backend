package com.tca.UserManager.repository;

import com.tca.UserManager.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PostRepository extends JpaRepository<Post, Long> {

    // Returns all posts newest-first
    List<Post> findAllByOrderByCreatedAtDesc();

    boolean existsByUserUserId(Integer userId);

    // Demo seeding identifies each sample post by its author and exact text.
    Optional<Post> findFirstByUserUserIdAndContent(Integer userId, String content);

    List<Post> findByUserUserId(Integer userId);

    List<Post> findByUserUserIdAndCreatedAtBefore(Integer userId, LocalDateTime cutoff);

    // Account deletion: the post stays (so replies keep their thread) but its text and
    // image, which may contain personal data, are removed.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Post p SET p.content = :placeholder, p.image = NULL WHERE p.user.userId = :userId")
    int redactForUser(@Param("userId") Integer userId, @Param("placeholder") String placeholder);
}
