package com.tca.UserManager.security;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.tca.UserManager.repository.UserRepository;

@Service("UserDetailsService")
public class CustomUserDetailsService implements UserDetailsService {

	@Autowired
	UserRepository userRepository;

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
		return userRepository.findByUsername(normalized)
				.map(CustomUserDetails::new)
				// Message is generic; the login endpoint reports bad credentials either way.
				.orElseThrow(() -> new UsernameNotFoundException("User not found"));
	}
}
