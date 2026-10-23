package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import com.tca.UserManager.entity.Comment;
import com.tca.UserManager.entity.Post;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.repository.CommentRepository;
import com.tca.UserManager.support.IntegrationTestBase;

/** Only disposable demo content (demo-authored, older than the retention window) is removed. */
class DemoContentCleanupIT extends IntegrationTestBase {

	@Autowired CommentRepository comments;

	private Comment comment(User author, Post post, String text) {
		Comment c = new Comment();
		c.setUser(author);
		c.setPost(post);
		c.setContent(text);
		return comments.saveAndFlush(c);
	}

	private void ageRow(String table, Long id) {
		jdbc.update("UPDATE " + table + " SET created_at = ? WHERE id = ?",
				Timestamp.valueOf(LocalDateTime.now().minusDays(2)), id);
	}

	@Test
	void removesOnlyOldDemoContentAndNeverOtherUsersData() throws Exception {
		User admin = createUser("root", Role.ADMIN);
		User demo = createUser("demo", Role.USER);
		User alice = createUser("alice", Role.USER);

		Post oldDemoPost = createPost(demo, "old demo post");
		Post oldDemoPostWithReply = createPost(demo, "old demo post someone replied to");
		Post newDemoPost = createPost(demo, "fresh demo post");
		Post oldAlicePost = createPost(alice, "alice's old post");
		Comment aliceReply = comment(alice, oldDemoPostWithReply, "alice replies to demo");
		Comment oldDemoComment = comment(demo, oldAlicePost, "old demo comment");
		Comment newDemoComment = comment(demo, oldAlicePost, "fresh demo comment");
		for (Post p : new Post[] { oldDemoPost, oldDemoPostWithReply, oldAlicePost }) ageRow("posts", p.getId());
		ageRow("comments", oldDemoComment.getId());

		mvc.perform(post("/admin/demo-content/cleanup").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(admin))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.commentsDeleted").value(1))
				.andExpect(jsonPath("$.postsDeleted").value(1))
				.andExpect(jsonPath("$.postsKept").value(1));

		assertThat(posts.findById(oldDemoPost.getId())).isEmpty();
		assertThat(posts.findById(oldDemoPostWithReply.getId())).isPresent(); // has alice's reply
		assertThat(posts.findById(newDemoPost.getId())).isPresent(); // within retention
		assertThat(posts.findById(oldAlicePost.getId())).isPresent(); // not demo content
		assertThat(comments.findById(aliceReply.getId())).isPresent();
		assertThat(comments.findById(oldDemoComment.getId())).isEmpty();
		assertThat(comments.findById(newDemoComment.getId())).isPresent();
	}

	@Test
	void onlyAdminsCanTriggerCleanup() throws Exception {
		User demo = createUser("demo", Role.USER);
		mvc.perform(post("/admin/demo-content/cleanup").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(demo))))
				.andExpect(status().isForbidden());
		mvc.perform(post("/admin/demo-content/cleanup")).andExpect(status().isUnauthorized());
	}

	@Autowired java.util.List<org.springframework.scheduling.config.ScheduledTaskHolder> scheduledTaskHolders;

	@Test
	void theScheduleIsOffByDefault() {
		// DEMO_CLEANUP_CRON defaults to "-", so Spring registers no scheduled task for it.
		assertThat(scheduledTaskHolders.stream().flatMap(h -> h.getScheduledTasks().stream()).map(Object::toString))
				.noneMatch(task -> task.contains("scheduledCleanup"));
	}
}
