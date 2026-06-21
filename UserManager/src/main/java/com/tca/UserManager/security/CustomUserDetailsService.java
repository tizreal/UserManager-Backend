package com.tca.UserManager.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import com.tca.UserManager.repository.UserRepository;
import java.util.Optional;

@Service("UserDetailsService")
public class CustomUserDetailsService implements UserDetailsService {

	@Autowired
	UserRepository userRepository;

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		// Fetch user from DB; throw exception if not found
		Optional<com.tca.UserManager.entity.User> opt = this.userRepository.findByUsername(username);

		if (opt.isEmpty()) {
			throw new UsernameNotFoundException("Username not found: " + username);
		}

		// Wrap the User entity in our CustomUserDetails adapter
		return new CustomUserDetails(opt.get());
	}
}