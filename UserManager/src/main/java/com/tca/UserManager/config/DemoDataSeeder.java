package com.tca.UserManager.config;

import java.sql.Timestamp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

		// The account recruiters log in with
		upsert("demo", "demo@usermanager.dev", "Demo", "User");

		// Filler users so the user list isn't empty
		upsert("ada", "ada.lovelace@usermanager.dev", "Ada", "Lovelace");
		upsert("alan", "alan.turing@usermanager.dev", "Alan", "Turing");
		upsert("grace", "grace.hopper@usermanager.dev", "Grace", "Hopper");

		System.out.println("Demo data seeded");
	}

	private void upsert(String username, String email, String firstName, String lastName) {
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
		user.setPassword(passwordEncoder.encode(demoPassword)); // reset every boot
		user.setEmailVerified(true); // skip email verification
		user.setUpdatedAt(now);

		userRepository.save(user);
	}
}