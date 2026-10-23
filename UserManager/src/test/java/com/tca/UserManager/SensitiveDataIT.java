package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.RequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.support.IntegrationTestBase;

/** No response, at any nesting depth, may contain credentials or another user's private data. */
class SensitiveDataIT extends IntegrationTestBase {

	private String body(RequestBuilder request) throws Exception {
		return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	@Test
	void noEndpointSerializesPasswordHashesOrTokenVersions() throws Exception {
		User admin = createUser("root", Role.ADMIN);
		User alice = createUser("alice", Role.USER);
		var post = createPost(alice, "hello");
		String aliceToken = bearer(tokenFor(alice));
		String adminToken = bearer(tokenFor(admin));

		List<String> bodies = new ArrayList<>();
		bodies.add(body(post("/user/login").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("username", "alice", "password", PASSWORD)))));
		bodies.add(body(get("/user/get").header(HttpHeaders.AUTHORIZATION, aliceToken)));
		bodies.add(body(post("/user/update").header(HttpHeaders.AUTHORIZATION, aliceToken)
				.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("firstName", "Al")))));
		bodies.add(body(get("/user/").header(HttpHeaders.AUTHORIZATION, adminToken)));
		bodies.add(body(get("/user/alice").header(HttpHeaders.AUTHORIZATION, adminToken)));
		bodies.add(body(post("/comments/create").header(HttpHeaders.AUTHORIZATION, aliceToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("content", "a comment", "post", Map.of("id", post.getId()))))));
		bodies.add(body(get("/posts/").header(HttpHeaders.AUTHORIZATION, aliceToken)));
		bodies.add(body(get("/comments/" + post.getId()).header(HttpHeaders.AUTHORIZATION, aliceToken)));
		bodies.add(body(post("/posts/" + post.getId() + "/like").header(HttpHeaders.AUTHORIZATION, aliceToken)));
		bodies.add(body(post("/user/signup").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("username", "newbie", "email", "newbie@example.com",
						"password", "newbie-password")))));

		for (String b : bodies) {
			assertThat(b).doesNotContainIgnoringCase("password")
					.doesNotContain("$2a$").doesNotContain("$2b$")
					.doesNotContainIgnoringCase("tokenVersion")
					.doesNotContainIgnoringCase("token_version");
		}
	}

	@Test
	void postAndCommentAuthorsExposeOnlyPublicIdentity() throws Exception {
		User alice = createUser("alice", Role.USER);
		User bob = createUser("bob", Role.USER);
		var post = createPost(alice, "alice's post");
		mvc.perform(post("/comments/create").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(alice)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("content", "self-comment", "post", Map.of("id", post.getId())))))
				.andExpect(status().isOk());

		String feed = body(get("/posts/").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(bob))));
		assertThat(feed).doesNotContain("alice@example.com").doesNotContain("5550001111");

		JsonNode author = json.readTree(feed).get(0).get("user");
		List<String> fields = new ArrayList<>();
		author.fieldNames().forEachRemaining(fields::add);
		assertThat(fields).containsExactlyInAnyOrder("userId", "username", "firstName", "lastName");

		JsonNode commentAuthor = json.readTree(feed).get(0).get("comments").get(0).get("user");
		List<String> commentFields = new ArrayList<>();
		commentAuthor.fieldNames().forEachRemaining(commentFields::add);
		assertThat(commentFields).containsExactlyInAnyOrder("userId", "username", "firstName", "lastName");
	}
}
