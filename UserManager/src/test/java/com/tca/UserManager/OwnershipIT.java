package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.support.IntegrationTestBase;

/** Resource ownership: posts can only be deleted by their author or an admin. */
class OwnershipIT extends IntegrationTestBase {

	@Test
	void otherUserCannotDeleteAPost() throws Exception {
		User alice = createUser("alice", Role.USER);
		User mallory = createUser("mallory", Role.USER);
		var post = createPost(alice, "alice's post");

		mvc.perform(delete("/posts/" + post.getId()).header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(mallory))))
				.andExpect(status().isForbidden());
		assertThat(posts.findById(post.getId())).isPresent();
	}

	@Test
	void authorCanDeleteTheirOwnPost() throws Exception {
		User alice = createUser("alice", Role.USER);
		var post = createPost(alice, "alice's post");
		mvc.perform(delete("/posts/" + post.getId()).header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(alice))))
				.andExpect(status().isOk());
		assertThat(posts.findById(post.getId())).isEmpty();
	}

	@Test
	void adminCanDeleteAnyPost() throws Exception {
		User alice = createUser("alice", Role.USER);
		User admin = createUser("root", Role.ADMIN);
		var post = createPost(alice, "alice's post");
		mvc.perform(delete("/posts/" + post.getId()).header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(admin))))
				.andExpect(status().isOk());
		assertThat(posts.findById(post.getId())).isEmpty();
	}

	@Test
	void missingPostsReturn404() throws Exception {
		String token = bearer(tokenFor(createUser("alice", Role.USER)));
		mvc.perform(delete("/posts/999").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isNotFound());
		mvc.perform(post("/posts/999/like").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isNotFound());
		mvc.perform(post("/comments/create").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("content", "hi", "post", Map.of("id", 999)))))
				.andExpect(status().isNotFound());
	}

	@Test
	void authorOfPostsAndCommentsIsAlwaysTheCaller() throws Exception {
		User alice = createUser("alice", Role.USER);
		User bob = createUser("bob", Role.USER);
		String aliceToken = bearer(tokenFor(alice));

		// Attempt to attribute the post and comment to bob.
		String created = mvc.perform(post("/posts/create").header(HttpHeaders.AUTHORIZATION, aliceToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("content", "hello", "user", Map.of("userId", bob.getUserId())))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.username").value("alice"))
				.andReturn().getResponse().getContentAsString();
		long postId = json.readTree(created).get("id").asLong();

		mvc.perform(post("/comments/create").header(HttpHeaders.AUTHORIZATION, aliceToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("content", "hi", "post", Map.of("id", postId),
						"user", Map.of("userId", bob.getUserId())))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.username").value("alice"));
	}

	@Test
	void invalidContentIsRejected() throws Exception {
		String token = bearer(tokenFor(createUser("alice", Role.USER)));
		mvc.perform(post("/posts/create").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("content", "  "))))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/posts/create").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("content", "x".repeat(5001)))))
				.andExpect(status().isBadRequest());
	}
}
