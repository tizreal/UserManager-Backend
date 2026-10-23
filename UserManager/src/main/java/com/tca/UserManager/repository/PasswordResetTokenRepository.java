package com.tca.UserManager.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.tca.UserManager.entity.PasswordResetToken;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

	Optional<PasswordResetToken> findByTokenHash(String tokenHash);

	/**
	 * Atomically marks an unused, unexpired token as used. Returns 1 for the single
	 * caller that wins, 0 for everyone else (already used, expired, or unknown).
	 * PostgreSQL row locking makes concurrent attempts on the same token serialize, and
	 * the losing UPDATE re-checks "used_at IS NULL" after the winner commits.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE PasswordResetToken t SET t.usedAt = :now "
			+ "WHERE t.tokenHash = :hash AND t.usedAt IS NULL AND t.expiresAt > :now")
	int consume(@Param("hash") String tokenHash, @Param("now") Instant now);

	/** Removes all of a user's reset tokens (account deletion). */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("DELETE FROM PasswordResetToken t WHERE t.userId = :userId")
	int deleteAllForUser(@Param("userId") Integer userId);

	/** Retires every outstanding token for a user (on new request or after a reset). */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE PasswordResetToken t SET t.usedAt = :now WHERE t.userId = :userId AND t.usedAt IS NULL")
	int invalidateAllForUser(@Param("userId") Integer userId, @Param("now") Instant now);
}
