package com.tca.UserManager.repository;

import com.tca.UserManager.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PostRepository extends JpaRepository<Post, Long> {

    // Returns all posts newest-first
    List<Post> findAllByOrderByCreatedAtDesc();

    boolean existsByUserUserId(Integer userId);

    List<Post> findByUserUserId(Integer userId);

    // Removes images (which may be personal photos) from an anonymized user's posts.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Post p SET p.image = NULL WHERE p.user.userId = :userId")
    int clearImagesForUser(@Param("userId") Integer userId);
}
