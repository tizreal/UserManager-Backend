package com.tca.UserManager.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.tca.UserManager.entity.AccountStatus;
import com.tca.UserManager.entity.Role;
import com.tca.UserManager.entity.User;

@Repository
public interface UserRepository extends JpaRepository<User, Integer> {

	// Usernames and emails are stored lowercase; callers normalize input before lookup.
	// The database also enforces case-insensitive uniqueness (see V2 migration).
	Optional<User> findByUsername(String username);

	Optional<User> findByEmail(String email);

	long countByRole(Role role);

	long countByRoleAndStatus(Role role, AccountStatus status);
}
