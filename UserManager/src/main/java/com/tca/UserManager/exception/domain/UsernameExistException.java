package com.tca.UserManager.exception.domain;

// Replace EmailExistException with the appropriate class name for each file: 
// EmailExistException, EmailNotFoundException, EmailNotVerifiedException, 
// UsernameExistException, UserNotFoundException 

public class UsernameExistException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public UsernameExistException(String message) {
		super(message); // Passes the message to RuntimeException
	}
}