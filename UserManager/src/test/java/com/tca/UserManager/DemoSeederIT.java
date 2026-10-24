package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;

import com.tca.UserManager.config.DemoDataSeeder;
import com.tca.UserManager.entity.Comment;
import com.tca.UserManager.entity.Post;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.repository.CommentRepository;
import com.tca.UserManager.service.DemoContentCleanupService;
import com.tca.UserManager.support.IntegrationTestBase;

/**
 * The demo seeder keeps the shared demo account safe and maintains a fixed, fictional sample
 * feed written by the filler accounts, without touching what visitors posted.
 */
class DemoSeederIT extends IntegrationTestBase {

	private static final List<String> FILLERS = List.of("ada", "alan", "grace");

	@Autowired DemoDataSeeder seeder;
	@Autowired CommentRepository comments;
	@Autowired DemoContentCleanupService cleanup;

	private void runSeeder() throws Exception {
		ReflectionTestUtils.setField(seeder, "seedDemo", true);
		ReflectionTestUtils.setField(seeder, "demoPassword", "public-demo-password");
		try {
			seeder.run();
		} finally {
			ReflectionTestUtils.setField(seeder, "seedDemo", false);
		}
	}

	private List<Post> samplePosts() {
		return posts.findAll().stream().filter(p -> FILLERS.contains(p.getUser().getUsername())).toList();
	}

	private List<Comment> sampleComments() {
		return comments.findAll().stream().filter(c -> FILLERS.contains(c.getUser().getUsername())).toList();
	}

	@Test
	void seedingPinsTheDemoRoleAndPasswordWithoutTouchingContent() throws Exception {
		User demo = createUser("demo", Role.ADMIN); // e.g. promoted by mistake
		var demoPost = createPost(demo, "visitor post");

		runSeeder();

		User demoAfter = users.findByUsername("demo").orElseThrow();
		assertThat(demoAfter.getRole()).isEqualTo(Role.USER);
		assertThat(passwordEncoder.matches("public-demo-password", demoAfter.getPassword())).isTrue();
		// Content cleanup is a separate, explicit/scheduled job (DemoContentCleanupService)
		assertThat(posts.findById(demoPost.getId())).isPresent();
	}

	@Test
	void fillerAccountsDoNotShareTheDemoPassword() throws Exception {
		runSeeder();
		for (String filler : FILLERS) {
			User u = users.findByUsername(filler).orElseThrow();
			assertThat(passwordEncoder.matches("public-demo-password", u.getPassword())).isFalse();
		}
	}

	@Test
	void seedsExactlyTheSampleFeedAndASecondRunAddsNothing() throws Exception {
		runSeeder();
		List<String> firstPosts = samplePosts().stream().map(Post::getContent).sorted().toList();
		List<String> firstComments = sampleComments().stream().map(Comment::getContent).sorted().toList();

		assertThat(firstPosts).containsExactlyInAnyOrderElementsOf(
				DemoDataSeeder.SAMPLE_POSTS.stream().map(DemoDataSeeder.SamplePost::content).toList());
		assertThat(firstComments).containsExactlyInAnyOrderElementsOf(
				DemoDataSeeder.SAMPLE_COMMENTS.stream().map(DemoDataSeeder.SampleComment::content).toList());

		runSeeder();

		assertThat(samplePosts().stream().map(Post::getContent).sorted().toList()).isEqualTo(firstPosts);
		assertThat(sampleComments().stream().map(Comment::getContent).sorted().toList()).isEqualTo(firstComments);
	}

	@Test
	void sampleRecordsAreInternallyConsistent() throws Exception {
		runSeeder();
		LocalDateTime now = LocalDateTime.now();

		for (Post post : samplePosts()) {
			var sample = DemoDataSeeder.SAMPLE_POSTS.stream()
					.filter(s -> s.content().equals(post.getContent())).findFirst().orElseThrow();
			assertThat(post.getUser().getUsername()).isEqualTo(sample.author());
			assertThat(post.getLikesCount()).isEqualTo(sample.likes()).isGreaterThanOrEqualTo(0);
			assertThat(post.getCreatedAt()).isBefore(now);
		}
		List<String[]> rows = jdbc.query("""
				SELECT c.content, u.user_name, p.content,
				       (c.created_at >= p.created_at AND c.created_at <= now()) AS ordered
				FROM comments c JOIN users u ON u.user_id = c.user_id JOIN posts p ON p.id = c.post_id
				WHERE u.user_name IN ('ada', 'alan', 'grace')""",
				(rs, i) -> new String[] { rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4) });
		assertThat(rows).hasSize(DemoDataSeeder.SAMPLE_COMMENTS.size());
		for (String[] row : rows) {
			var sample = DemoDataSeeder.SAMPLE_COMMENTS.stream()
					.filter(s -> s.content().equals(row[0])).findFirst().orElseThrow();
			assertThat(row[1]).isEqualTo(sample.author());
			assertThat(row[2]).isEqualTo(sample.postContent());
			assertThat(row[3]).isEqualTo("t");
		}
	}

	@Test
	void aDeletedSamplePostIsRecreatedAndVisitorContentIsKept() throws Exception {
		runSeeder();
		User demo = createUser("demo-visitor", Role.USER);
		Post visitorPost = createPost(demo, "visitor post");
		Post sample = samplePosts().stream()
				.filter(p -> p.getContent().equals(DemoDataSeeder.SAMPLE_POSTS.get(2).content())).findFirst().orElseThrow();
		posts.delete(sample); // its comments go with it

		runSeeder();

		assertThat(samplePosts()).hasSize(DemoDataSeeder.SAMPLE_POSTS.size());
		assertThat(sampleComments()).hasSize(DemoDataSeeder.SAMPLE_COMMENTS.size());
		assertThat(posts.findById(visitorPost.getId())).isPresent();
	}

	@Test
	void visitorLikesOnSamplePostsAreNotReset() throws Exception {
		runSeeder();
		Post sample = samplePosts().get(0);
		sample.setLikesCount(sample.getLikesCount() + 10);
		posts.saveAndFlush(sample);

		runSeeder();

		assertThat(posts.findById(sample.getId()).orElseThrow().getLikesCount()).isEqualTo(sample.getLikesCount());
	}

	@Test
	void concurrentRunsProduceASingleSampleFeed() throws Exception {
		ReflectionTestUtils.setField(seeder, "seedDemo", true);
		ReflectionTestUtils.setField(seeder, "demoPassword", "public-demo-password");
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> runs = List.of(
					pool.submit(() -> { start.await(); seeder.run(); return null; }),
					pool.submit(() -> { start.await(); seeder.run(); return null; }),
					pool.submit(() -> { start.await(); seeder.run(); return null; }));
			start.countDown();
			for (Future<?> run : runs) {
				run.get();
			}
		} finally {
			pool.shutdown();
			ReflectionTestUtils.setField(seeder, "seedDemo", false);
		}

		assertThat(samplePosts()).hasSize(DemoDataSeeder.SAMPLE_POSTS.size());
		assertThat(sampleComments()).hasSize(DemoDataSeeder.SAMPLE_COMMENTS.size());
		for (String filler : FILLERS) {
			assertThat(users.findAll().stream().filter(u -> filler.equals(u.getUsername()))).hasSize(1);
		}
	}

	@Test
	void demoCleanupKeepsTheSampleFeed() throws Exception {
		runSeeder();
		jdbc.update("UPDATE posts SET created_at = ?", Timestamp.valueOf(LocalDateTime.now().minusDays(3)));
		jdbc.update("UPDATE comments SET created_at = ?", Timestamp.valueOf(LocalDateTime.now().minusDays(3)));

		cleanup.cleanup();

		assertThat(samplePosts()).hasSize(DemoDataSeeder.SAMPLE_POSTS.size());
		assertThat(sampleComments()).hasSize(DemoDataSeeder.SAMPLE_COMMENTS.size());
	}

	@Test
	void theDemoAccountCannotDeleteSamplePosts() throws Exception {
		runSeeder();
		User demo = users.findByUsername("demo").orElseThrow();
		Post sample = samplePosts().get(0);

		mvc.perform(delete("/posts/" + sample.getId()).header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(demo))))
				.andExpect(status().isForbidden());
		assertThat(posts.findById(sample.getId())).isPresent();
	}
}
