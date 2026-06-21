package com.tca.UserManager.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.service.UserService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Optional;
import static org.springframework.http.HttpStatus.OK;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.JsonNode;


@RestController          // Returns JSON directly (no view layer) 
@RequestMapping("/user") // All endpoints start with /api/user (+ /api from context-path) 
public class UserController { 
 
    final Logger logger = LoggerFactory.getLogger(this.getClass()); 
 
    @Autowired UserService userService; 
 
    // GET /api/user/  →  Returns all users 
    @GetMapping("/") 
    public List<User> listUsers() { 
        logger.debug("listUsers() invoked"); 
        return this.userService.listUsers(); 

    } 
 
    // GET /api/user/{username}  →  Find user by username 
    @GetMapping("/{username}") 
    public Optional<User> findByUsername(@PathVariable String username) { 
        logger.debug("findByUsername() username={}", username); 
        return this.userService.findByUsername(username); 
    } 
 
    // DELETE /api/user/{userId}  →  Delete user by ID 
    @DeleteMapping("/{userId}") 
    public String deleteUserById(@PathVariable Integer userId) { 
        logger.debug("deleteUserById() userId={}", userId); 
        userService.deleteUserById(userId); 
        return "Deleted User"; 
    } 
 
    // POST /api/user/signup  →  Register a new user 
    @PostMapping("/signup") 
    public User signup(@RequestBody User user) { 
        logger.debug("signup() username={}", user.getUsername()); 
        return this.userService.signup(user); 
    } 
 
    // GET /api/user/verify/email  →  Verify email (token in Authorization header) 
    @GetMapping("/verify/email") 
    public void verifyEmail() { 
        logger.debug("verifyEmail() invoked"); 
        this.userService.verifyEmail(); 
    } 
 
    // POST /api/user/login  →  Authenticate; JWT returned in Authorization header 
    @PostMapping("/login") 
    public ResponseEntity<User> login(@RequestBody User user) { 
        logger.debug("login() username={}", user.getUsername()); 
        user = this.userService.authenticate(user); 
        HttpHeaders jwtHeader = 
this.userService.generateJwtHeader(user.getUsername()); 
        return new ResponseEntity<>(user, jwtHeader, OK); 
    } 
 
    // GET /api/user/reset/{emailId}  →  Send password reset email 
    @GetMapping("/reset/{emailId}") 
    public void sendResetPasswordEmail(@PathVariable String emailId) { 
        logger.debug("sendResetPasswordEmail() email={}", emailId); 
        this.userService.sendResetPasswordEmail(emailId); 
    } 
 
    // POST /api/user/reset  →  Reset password (token + new password in body) 
    @PostMapping("/reset") 
    public void passwordReset(@RequestBody JsonNode json) { 
        String password = json.get("password").asText(); 
        String token    = json.get("token").asText(); 
        logger.debug("passwordReset() invoked"); 
        this.userService.resetPassword(token, password); 
    } 
 
    // GET /api/user/get  →  Return authenticated user's own data 
    @GetMapping("/get") 
    public User getUser() { 
        logger.debug("getUser() invoked"); 
        return this.userService.getUser(); 
    } 
 
    // POST /api/user/update  →  Update authenticated user's profile 

    @PostMapping("/update") 
    public User updateUser(@RequestBody User user) { 
        logger.debug("updateUser() invoked"); 
        return this.userService.updateUser(user); 
    } 
}