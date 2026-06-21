package com.tca.UserManager.repository; 
 
import java.util.Optional; 
import org.springframework.data.jpa.repository.JpaRepository; 
import org.springframework.stereotype.Repository; 
import com.tca.UserManager.entity.User; 

 
@Repository  // Marks this as a Spring Data repository — Spring creates the implementation 
public interface UserRepository extends JpaRepository<User, Integer> { 
 
    // Spring generates the SQL automatically from the method name: 
    // SELECT * FROM Users WHERE user_name = ? 
    Optional<User> findByUsername(String username); 
 
    // SELECT * FROM Users WHERE email = ? 
    Optional<User> findByEmail(String email); 
 
    // DELETE FROM Users WHERE user_id = ? 
    void deleteById(Integer userId); 
 
}

/*
�  HOW SPRING DATA JPA WORKS 
JpaRepository<User, Integer> means: this repository manages User entities whose primary key is of 
type Integer. By extending it, you automatically get: save(), findById(), findAll(), deleteById(), 
existsById(), count() — all implemented by Spring without writing a single line of SQL. 
 * 
 * */
