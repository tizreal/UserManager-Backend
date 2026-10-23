package com.tca.UserManager.service;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.tca.UserManager.entity.User;

/**
 * The shared demo login is public, so its credentials and profile must not be
 * changeable by visitors. It is a normal USER: it gets no admin access.
 */
@Component
public class DemoAccountPolicy {

	private final String demoUsername;

	public DemoAccountPolicy(@Value("${app.demo.username:demo}") String demoUsername) {
		this.demoUsername = demoUsername == null ? "" : demoUsername.trim().toLowerCase(Locale.ROOT);
	}

	public boolean isDemo(User user) {
		return !demoUsername.isEmpty() && user != null && demoUsername.equals(user.getUsername());
	}
}
