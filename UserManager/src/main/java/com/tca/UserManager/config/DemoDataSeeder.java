package com.tca.UserManager.config;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tca.UserManager.entity.Comment;
import com.tca.UserManager.entity.Post;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.repository.CommentRepository;
import com.tca.UserManager.repository.PostRepository;
import com.tca.UserManager.repository.UserRepository;

@Component
public class DemoDataSeeder implements CommandLineRunner {

	/** A fictional sample post: author, text, initial likes, and how long ago it was posted. */
	public record SamplePost(String author, String content, int likes, int hoursAgo) {
	}

	/** A fictional sample comment on the sample post with the given text. */
	public record SampleComment(String postContent, String author, String content, int minutesAfterPost) {
	}

	// Fixed sample feed, written by the filler accounts. Each record is identified by its
	// author and exact text, so seeding is idempotent and a deleted sample is recreated.
	public static final List<SamplePost> SAMPLE_POSTS = List.of(
			new SamplePost("grace", "Shipped the new onboarding checklist today. Sign-up to first post is down to three steps.", 4, 72),
			new SamplePost("alan", "Code review tip: ask what happens when the same request arrives twice. Retries and double-clicks find a lot of bugs.", 6, 50),
			new SamplePost("ada", "Book club pick for next month: anything about the history of computing. Suggestions welcome in the comments.", 3, 44),
			new SamplePost("grace", "Reminder: staging is frozen on Friday afternoon while we rehearse the release.", 5, 26),
			new SamplePost("alan", "Wrote up how password-reset links work here: single use, short expiry, and stored only as a hash.", 2, 20),
			new SamplePost("ada", "Welcome to everyone trying the demo! Post, comment and like freely. Posts made with the demo account are cleared automatically.", 7, 5));

	public static final List<SampleComment> SAMPLE_COMMENTS = List.of(
			new SampleComment(SAMPLE_POSTS.get(0).content(), "ada", "Nice. The empty states read much better too.", 40),
			new SampleComment(SAMPLE_POSTS.get(1).content(), "ada", "And what happens when they arrive out of order.", 25),
			new SampleComment(SAMPLE_POSTS.get(2).content(), "alan", "Anything on the early programming languages gets my vote.", 30),
			new SampleComment(SAMPLE_POSTS.get(2).content(), "grace", "Seconded. I can lead the first discussion.", 75),
			new SampleComment(SAMPLE_POSTS.get(3).content(), "alan", "Thanks for the heads-up. I'll hold my merge until Monday.", 15),
			new SampleComment(SAMPLE_POSTS.get(5).content(), "grace", "Tip: the demo account's profile is read-only, but everything in the feed works.", 10));

	// Serializes seeding across concurrent runs or instances (PostgreSQL advisory lock).
	private static final long SEED_LOCK_KEY = 0x55534D5345454431L;

	private final UserRepository userRepository;
	private final PostRepository postRepository;
	private final CommentRepository commentRepository;
	private final JdbcTemplate jdbc;
	private final PasswordEncoder passwordEncoder;

	@Value("${app.seed-demo:false}")
	private boolean seedDemo;

	@Value("${DEMO_PASSWORD:Demo123!}")
	private String demoPassword;

	public DemoDataSeeder(UserRepository userRepository, PostRepository postRepository,
			CommentRepository commentRepository, JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
		this.userRepository = userRepository;
		this.postRepository = postRepository;
		this.commentRepository = commentRepository;
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	@Transactional
	public void run(String... args) {
		if (!seedDemo)
			return;
		jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, SEED_LOCK_KEY);

		// The account recruiters log in with. Always a plain USER; its password is
		// reset to the public demo password on every boot.
		upsert("demo", "demo@usermanager.dev", "Demo", "User", demoPassword);

		// Visitors' demo content is not touched here; see DemoContentCleanupService.

		// Filler users so lists aren't empty. They get a random password each boot so
		// nobody can sign in as them with the public demo password.
		upsert("ada", "ada.lovelace@usermanager.dev", "Ada", "Lovelace", randomPassword());
		upsert("alan", "alan.turing@usermanager.dev", "Alan", "Turing", randomPassword());
		upsert("grace", "grace.hopper@usermanager.dev", "Grace", "Hopper", randomPassword());

		seedSampleFeed();

		System.out.println("Demo data seeded");
	}

	/** Creates any missing sample post or comment; existing ones (and their likes) are left as they are. */
	private void seedSampleFeed() {
		LocalDateTime now = LocalDateTime.now();
		for (SamplePost sample : SAMPLE_POSTS) {
			User author = userRepository.findByUsername(sample.author()).orElseThrow();
			if (postRepository.findFirstByUserUserIdAndContent(author.getUserId(), sample.content()).isPresent()) {
				continue;
			}
			Post post = new Post();
			post.setUser(author);
			post.setContent(sample.content());
			post.setLikesCount(sample.likes());
			post = postRepository.saveAndFlush(post);
			// created_at is set on insert; backdate it so the feed spans a few days.
			jdbc.update("UPDATE posts SET created_at = ? WHERE id = ?",
					Timestamp.valueOf(now.minusHours(sample.hoursAgo())), post.getId());
		}
		for (SampleComment sample : SAMPLE_COMMENTS) {
			SamplePost parent = SAMPLE_POSTS.stream()
					.filter(p -> p.content().equals(sample.postContent())).findFirst().orElseThrow();
			User postAuthor = userRepository.findByUsername(parent.author()).orElseThrow();
			Post post = postRepository.findFirstByUserUserIdAndContent(postAuthor.getUserId(), parent.content())
					.orElseThrow();
			User author = userRepository.findByUsername(sample.author()).orElseThrow();
			if (commentRepository.existsByPostIdAndUserUserIdAndContent(post.getId(), author.getUserId(), sample.content())) {
				continue;
			}
			Comment comment = new Comment();
			comment.setPost(post);
			comment.setUser(author);
			comment.setContent(sample.content());
			comment = commentRepository.saveAndFlush(comment);
			LocalDateTime postedAt = now.minusHours(parent.hoursAgo()).plusMinutes(sample.minutesAfterPost());
			jdbc.update("UPDATE comments SET created_at = ? WHERE id = ?",
					Timestamp.valueOf(postedAt.isBefore(now) ? postedAt : now), comment.getId());
		}
	}

	private static String randomPassword() {
		byte[] bytes = new byte[24];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private void upsert(String username, String email, String firstName, String lastName, String password) {
		Timestamp now = new Timestamp(System.currentTimeMillis());

		User user = userRepository.findByUsername(username).orElseGet(() -> {
			User u = new User();
			u.setCreatedAt(now);
			return u;
		});

		user.setUsername(username);
		user.setEmail(email);
		user.setFirstName(firstName);
		user.setLastName(lastName);
		user.setPhoneNumber("5550100000");
		user.setCountryCode("+1");
		user.setPassword(passwordEncoder.encode(password)); // reset every boot
		user.setRole(Role.USER);
		user.setEmailVerified(true); // skip email verification
		user.setUpdatedAt(now);

		userRepository.save(user);
	}
}