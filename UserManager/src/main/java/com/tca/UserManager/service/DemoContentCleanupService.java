package com.tca.UserManager.service;

import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tca.UserManager.entity.Post;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.repository.CommentRepository;
import com.tca.UserManager.repository.PostRepository;
import com.tca.UserManager.repository.UserRepository;

/**
 * Removes disposable demo content: posts and comments written by the shared demo account
 * that are older than the retention window (DEMO_CONTENT_RETENTION, default 24h).
 *
 * Only demo-authored data is touched. A demo post that another (non-demo) user has
 * commented on is kept, because deleting it would also delete their comment.
 *
 * Runs only when triggered explicitly by an admin (POST /admin/demo-content/cleanup) or on
 * the schedule in DEMO_CLEANUP_CRON, which is disabled ("-") by default.
 */
@Service
public class DemoContentCleanupService {

	private static final Logger logger = LoggerFactory.getLogger(DemoContentCleanupService.class);

	public record Result(int commentsDeleted, int postsDeleted, int postsKept) {
	}

	private final UserRepository userRepository;
	private final PostRepository postRepository;
	private final CommentRepository commentRepository;
	private final DemoAccountPolicy demoPolicy;
	private final String demoUsername;
	private final Duration retention;

	public DemoContentCleanupService(UserRepository userRepository, PostRepository postRepository,
			CommentRepository commentRepository, DemoAccountPolicy demoPolicy,
			@Value("${app.demo.username:demo}") String demoUsername,
			@Value("${app.demo.content-retention:PT24H}") Duration retention) {
		this.userRepository = userRepository;
		this.postRepository = postRepository;
		this.commentRepository = commentRepository;
		this.demoPolicy = demoPolicy;
		this.demoUsername = demoUsername.trim().toLowerCase();
		this.retention = retention;
	}

	@Scheduled(cron = "${app.demo.cleanup-cron:-}")
	public void scheduledCleanup() {
		cleanup();
	}

	@Transactional
	public Result cleanup() {
		User demo = userRepository.findByUsername(demoUsername).filter(demoPolicy::isDemo).orElse(null);
		if (demo == null) {
			return new Result(0, 0, 0);
		}
		LocalDateTime cutoff = LocalDateTime.now().minus(retention);
		Integer demoId = demo.getUserId();

		int commentsDeleted = commentRepository.deleteByUserIdCreatedBefore(demoId, cutoff);

		int postsDeleted = 0;
		int postsKept = 0;
		for (Post post : postRepository.findByUserUserIdAndCreatedAtBefore(demoId, cutoff)) {
			if (commentRepository.existsByPostIdAndUserUserIdNot(post.getId(), demoId)) {
				postsKept++;
			} else {
				postRepository.delete(post);
				postsDeleted++;
			}
		}
		logger.info("Demo content cleanup: {} comments and {} posts removed, {} posts kept (have other users' comments)",
				commentsDeleted, postsDeleted, postsKept);
		return new Result(commentsDeleted, postsDeleted, postsKept);
	}
}
