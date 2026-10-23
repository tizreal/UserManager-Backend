package com.tca.UserManager.config;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.repository.UserRepository;

@Component
public class DemoDataSeeder implements CommandLineRunner {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	@Value("${app.seed-demo:false}")
	private boolean seedDemo;

	@Value("${DEMO_PASSWORD:Demo123!}")
	private String demoPassword;

	public DemoDataSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	@Transactional
	public void run(String... args) {
		if (!seedDemo)
			return;

		// The account recruiters log in with. Always a plain USER; its password is
		// reset to the public demo password on every boot.
		upsert("demo", "demo@usermanager.dev", "Demo", "User", demoPassword);

		// Filler users so lists aren't empty. They get a random password each boot so
		// nobody can sign in as them with the public demo password.
		upsert("ada", "ada.lovelace@usermanager.dev", "Ada", "Lovelace", randomPassword());
		upsert("alan", "alan.turing@usermanager.dev", "Alan", "Turing", randomPassword());
		upsert("grace", "grace.hopper@usermanager.dev", "Grace", "Hopper", randomPassword());

		System.out.println("Demo data seeded");
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