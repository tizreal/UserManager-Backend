package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.tca.UserManager.entity.User;
import com.tca.UserManager.support.IntegrationTestBase;

/** Regression: the complete legitimate user journey still works end to end. */
class UserJourneyIT extends IntegrationTestBase {

	@Test
	void signupVerifyLoginProfilePostsCommentsAndPasswordChange() throws Exception {
		// Sign up (same payload shape the frontend sends)
		mvc.perform(post("/user/signup").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("firstName", "Grace", "lastName", "Hopper", "username", "GHopper",
						"email", "Grace@Example.com", "password", "cobol-forever", "phoneNumber", "555 010 0000"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("ghopper"))
				.andExpect(jsonPath("$.email").value("grace@example.com"));

		// Verify email with the token from the verification email
		ArgumentCaptor<String> verification = ArgumentCaptor.forClass(String.class);
		verify(emailService).sendVerificationEmail(any(User.class), verification.capture());
		mvc.perform(get("/user/verify/email").header(HttpHeaders.AUTHORIZATION, bearer(verification.getValue())))
				.andExpect(status().isOk());

		// Log in and read own profile
		String token = bearer(login("ghopper", "cobol-forever"));
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.emailVerified").value(true))
				.andExpect(jsonPath("$.role").value("USER"));

		// Update profile fields (frontend sends the whole profile, including read-only fields)
		mvc.perform(post("/user/update").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("firstName", "Rear Admiral Grace", "username", "ghopper",
						"email", "grace@example.com", "password", ""))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.firstName").value("Rear Admiral Grace"))
				.andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION));

		// Posts, likes, comments
		String created = mvc.perform(post("/posts/create").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("content", "First post", "image", "data:image/png;base64,iVBORw0KGgo="))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.likesCount").value(0))
				.andReturn().getResponse().getContentAsString();
		long postId = json.readTree(created).get("id").asLong();

		mvc.perform(post("/posts/" + postId + "/like").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(jsonPath("$.likesCount").value(1));
		mvc.perform(post("/posts/" + postId + "/unlike").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(jsonPath("$.likesCount").value(0));
		mvc.perform(post("/comments/create").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("content", "Nice", "post", Map.of("id", postId)))))
				.andExpect(status().isOk());
		mvc.perform(get("/comments/" + postId).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(jsonPath("$[0].content").value("Nice"))
				.andExpect(jsonPath("$[0].user.username").value("ghopper"));
		mvc.perform(get("/posts/").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(jsonPath("$[0].content").value("First post"))
				.andExpect(jsonPath("$[0].image").value("data:image/png;base64,iVBORw0KGgo="))
				.andExpect(jsonPath("$[0].comments[0].content").value("Nice"));

		// Change password: a fresh token comes back and the old session is signed out
		String newToken = mvc.perform(post("/user/update").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("password", "new-cobol-password"))))
				.andExpect(status().isOk())
				.andExpect(header().exists(HttpHeaders.AUTHORIZATION))
				.andReturn().getResponse().getHeader(HttpHeaders.AUTHORIZATION);
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isUnauthorized());
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer(newToken))).andExpect(status().isOk());
		assertThat(login("ghopper", "new-cobol-password")).isNotBlank();

		// Delete own post
		mvc.perform(delete("/posts/" + postId).header(HttpHeaders.AUTHORIZATION, bearer(newToken)))
				.andExpect(status().isOk());
		assertThat(posts.count()).isZero();
	}
}
