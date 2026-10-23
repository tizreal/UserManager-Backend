package com.tca.UserManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.tca.UserManager.entity.AccountStatus;
import com.tca.UserManager.entity.Comment;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.repository.CommentRepository;
import com.tca.UserManager.support.IntegrationTestBase;

import org.springframework.beans.factory.annotation.Autowired;

/** Deactivation (reversible) and deletion (irreversible anonymization). */
class AccountLifecycleIT extends IntegrationTestBase {

	@Autowired CommentRepository comments;

	private void expectLoginFails(String username, String password) throws Exception {
		mvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("username", username, "password", password))))
				.andExpect(status().isBadRequest());
	}

	// ---- Deactivation ----

	@Test
	void deactivationBlocksLoginAndSessionsButKeepsContent_andReactivationRestoresLogin() throws Exception {
		User admin = createUser("root", Role.ADMIN);
		User bob = createUser("bob", Role.USER);
		var post = createPost(bob, "bob's post");
		String bobSession = tokenFor(bob);
		String adminToken = bearer(tokenFor(admin));

		mvc.perform(post("/user/" + bob.getUserId() + "/deactivate").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DISABLED"));

		expectLoginFails("bob", PASSWORD);
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer(bobSession)))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/user/reset/bob@example.com")).andExpect(status().isOk());
		verify(emailService, never()).sendResetPasswordEmail(any(), anyString());
		// Content and attribution are untouched
		mvc.perform(get("/posts/").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(jsonPath("$[0].id").value(post.getId()))
				.andExpect(jsonPath("$[0].user.username").value("bob"));

		mvc.perform(post("/user/" + bob.getUserId() + "/reactivate").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		assertThat(login("bob", PASSWORD)).isNotBlank();
		// Sessions from before the deactivation stay revoked
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer(bobSession)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void onlyAdminsCanDeactivateOrReactivate() throws Exception {
		User alice = createUser("alice", Role.USER);
		User bob = createUser("bob", Role.USER);
		String token = bearer(tokenFor(alice));
		mvc.perform(post("/user/" + bob.getUserId() + "/deactivate").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isForbidden());
		mvc.perform(post("/user/" + bob.getUserId() + "/reactivate").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isForbidden());
		assertThat(users.findById(bob.getUserId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);
	}

	@Test
	void adminCannotDeactivateThemselves() throws Exception {
		User admin = createUser("root", Role.ADMIN);
		mvc.perform(post("/user/" + admin.getUserId() + "/deactivate")
				.header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(admin))))
				.andExpect(status().isConflict());
	}

	@Test
	void anotherAdminCanBeDeactivatedWhileOneActiveAdminRemains() throws Exception {
		User root = createUser("root", Role.ADMIN);
		User second = createUser("second", Role.ADMIN);
		mvc.perform(post("/user/" + second.getUserId() + "/deactivate")
				.header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(root))))
				.andExpect(status().isOk());
	}

	// ---- Deletion ----

	@Test
	void adminDeletionAnonymizesPersonalDataAndPreservesDiscussionStructure() throws Exception {
		User admin = createUser("root", Role.ADMIN);
		User bob = createUser("bob", Role.USER);
		User alice = createUser("alice", Role.USER);
		var bobPost = createPost(bob, "bob's post");
		bobPost.setImage("data:image/png;base64,personal-photo");
		posts.saveAndFlush(bobPost);
		var alicePost = createPost(alice, "alice's post");
		Comment reply = new Comment();
		reply.setContent("bob replies");
		reply.setPost(alicePost);
		reply.setUser(bob);
		comments.saveAndFlush(reply);
		jdbc.update("INSERT INTO password_reset_tokens (user_id, token_hash, expires_at, created_at) "
				+ "VALUES (?, 'h', now() + interval '10 minutes', now())", bob.getUserId());
		String bobSession = tokenFor(bob);

		mvc.perform(delete("/user/" + bob.getUserId()).header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(admin))))
				.andExpect(status().isOk());

		User after = users.findById(bob.getUserId()).orElseThrow();
		assertThat(after.getStatus()).isEqualTo(AccountStatus.DELETED);
		assertThat(after.getDeletedAt()).isNotNull();
		assertThat(after.getUsername()).isEqualTo("deleted-user-" + bob.getUserId());
		assertThat(after.getEmail()).isNull();
		assertThat(after.getPendingEmail()).isNull();
		assertThat(after.getFirstName()).isNull();
		assertThat(after.getLastName()).isNull();
		assertThat(after.getPhoneNumber()).isNull();
		assertThat(after.getCountryCode()).isNull();
		assertThat(passwordEncoder.matches(PASSWORD, after.getPassword())).isFalse();
		assertThat(after.getRole()).isEqualTo(Role.USER);

		// Sessions and reset tokens are gone; the old username no longer logs in
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, bearer(bobSession)))
				.andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE user_id = ?",
				Integer.class, bob.getUserId())).isZero();
		expectLoginFails("bob", PASSWORD);

		// Posts and comments remain, attributed to the anonymized account; images are removed
		assertThat(posts.findById(bobPost.getId()).orElseThrow().getImage()).isNull();
		mvc.perform(get("/comments/" + alicePost.getId()).header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(alice))))
				.andExpect(jsonPath("$[0].content").value("bob replies"))
				.andExpect(jsonPath("$[0].user.username").value("deleted-user-" + bob.getUserId()))
				.andExpect(jsonPath("$[0].user.firstName").doesNotExist());

		// The email address is free again
		mvc.perform(post("/user/signup").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("username", "bob2", "email", "bob@example.com", "password", "fresh-password"))))
				.andExpect(status().isOk());
	}

	@Test
	void deletedAccountsCannotBeReactivatedOrDeletedAgain() throws Exception {
		User admin = createUser("root", Role.ADMIN);
		User bob = createUser("bob", Role.USER);
		String adminToken = bearer(tokenFor(admin));
		mvc.perform(delete("/user/" + bob.getUserId()).header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk());
		mvc.perform(post("/user/" + bob.getUserId() + "/reactivate").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isConflict());
		mvc.perform(delete("/user/" + bob.getUserId()).header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isConflict());
	}

	@Test
	void usersCanDeleteTheirOwnAccountWithTheirCurrentPassword() throws Exception {
		User bob = createUser("bob", Role.USER);
		String token = bearer(tokenFor(bob));

		mvc.perform(post("/user/delete-account").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("currentPassword", "wrong-password"))))
				.andExpect(status().isBadRequest());
		assertThat(users.findById(bob.getUserId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);

		mvc.perform(post("/user/delete-account").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("currentPassword", PASSWORD))))
				.andExpect(status().isOk());
		assertThat(users.findById(bob.getUserId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.DELETED);
		mvc.perform(get("/user/get").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isUnauthorized());
	}

	@Test
	void theLastActiveAdminCannotDeleteThemselves() throws Exception {
		User admin = createUser("root", Role.ADMIN);
		mvc.perform(post("/user/delete-account").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(admin)))
				.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("currentPassword", PASSWORD))))
				.andExpect(status().isConflict());
		assertThat(users.findById(admin.getUserId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);
	}

	@Test
	void theDemoAccountCannotBeDeleted() throws Exception {
		User admin = createUser("root", Role.ADMIN);
		User demo = createUser("demo", Role.USER);
		mvc.perform(delete("/user/" + demo.getUserId()).header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(admin))))
				.andExpect(status().isConflict());
		mvc.perform(post("/user/delete-account").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(demo)))
				.contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("currentPassword", PASSWORD))))
				.andExpect(status().isForbidden());
		assertThat(users.findById(demo.getUserId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);
	}

	@Test
	void reservedDeletedUsernamesCannotBeRegistered() throws Exception {
		mvc.perform(post("/user/signup").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(Map.of("username", "deleted-user-1", "email", "x@example.com", "password", "some-password"))))
				.andExpect(status().isBadRequest());
	}
}
