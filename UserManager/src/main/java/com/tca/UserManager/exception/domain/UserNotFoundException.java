package com.tca.UserManager.exception.domain; 
 
// Replace EmailExistException with the appropriate class name for each file: 
// EmailExistException, EmailNotFoundException, EmailNotVerifiedException, 
// UsernameExistException, UserNotFoundException 
 
public class UserNotFoundException extends RuntimeException { 
    private static final long serialVersionUID = 1L; 
 
    public UserNotFoundException(String message) { 
        super(message);  // Passes the message to RuntimeException 
    } 
} 