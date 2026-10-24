package com.tca.UserManager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync // Enables @Async — emails send in background threads
@EnableScheduling // Optional scheduled jobs (e.g. demo content cleanup, off by default)
@SpringBootApplication // Auto-configuration + component scanning
public class UserManagerApplication {

	public static void main(String[] args) {
		SpringApplication.run(UserManagerApplication.class, args);
	}
}
