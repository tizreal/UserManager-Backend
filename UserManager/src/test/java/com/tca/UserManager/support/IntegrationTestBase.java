package com.tca.UserManager.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tca.UserManager.entity.Post;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.repository.PostRepository;
import com.tca.UserManager.repository.UserRepository;
import com.tca.UserManager.security.JwtService;
import com.tca.UserManager.service.EmailService;

/**
 * Shared setup for integration tests: one real PostgreSQL container for the whole run
 * (schema created by the Flyway migrations, validated by Hibernate), one Spring
 * context, and a clean database before every test. Email is mocked so tests can
 * capture verification and reset tokens.
 */
@SpringBootTest(properties = {
		"JWT_SECRET=integration-test-secret-integration-test-secret-integration-test-secret",
		"RESEND_API_KEY=test-key",
		"MAIL_HOST=localhost", "MAIL_USERNAME=test", "MAIL_PASSWORD=test",
		"DATABASE_URL=jdbc:postgresql://overridden", "DATABASE_USERNAME=overridden", "DATABASE_PASSWORD=overridden",
		"app.seed-demo=false",
		"app.demo.username=demo" })
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

	protected static final String PASSWORD = "correct-horse-battery";

	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired protected MockMvc mvc;
	@Autowired protected ObjectMapper json;
	@Autowired protected JdbcTemplate jdbc;
	@Autowired protected UserRepository users;
	@Autowired protected PostRepository posts;
	@Autowired protected PasswordEncoder passwordEncoder;
	@Autowired protected JwtService jwtService;

	@MockitoBean protected EmailService emailService;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("TRUNCATE comments, posts, password_reset_tokens, users RESTART IDENTITY CASCADE");
	}

	// ---- Fixtures ----

	protected User createUser(String username, Role role) {
		return createUser(username, role, true);
	}

	protected User createUser(String username, Role role, boolean verified) {
		Timestamp now = Timestamp.from(Instant.now());
		User u = new User();
		u.setUsername(username);
		u.setEmail(username + "@example.com");
		u.setPassword(passwordEncoder.encode(PASSWORD));
		u.setFirstName(Character.toUpperCase(username.charAt(0)) + username.substring(1));
		u.setLastName("Tester");
		u.setPhoneNumber("5550001111");
		u.setEmailVerified(verified);
		u.setRole(role);
		u.setCreatedAt(now);
		u.setUpdatedAt(now);
		return users.saveAndFlush(u);
	}

	protected Post createPost(User author, String content) {
		Post p = new Post();
		p.setUser(author);
		p.setContent(content);
		return posts.saveAndFlush(p);
	}

	/** A valid access token for the user's current state in the database. */
	protected String tokenFor(User user) {
		return jwtService.generateAccessToken(users.findById(user.getUserId()).orElseThrow());
	}

	protected static String bearer(String token) {
		return "Bearer " + token;
	}

	protected String toJson(Object value) throws Exception {
		return json.writeValueAsString(value);
	}

	/** Logs in through the real endpoint and returns the issued access token. */
	protected String login(String username, String password) throws Exception {
		MvcResult result = mvc.perform(post("/user/login").contentType(MediaType.APPLICATION_JSON)
				.content(toJson(java.util.Map.of("username", username, "password", password))))
				.andExpect(status().isOk()).andReturn();
		return result.getResponse().getHeader(HttpHeaders.AUTHORIZATION);
	}
}
