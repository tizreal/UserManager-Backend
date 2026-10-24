package com.tca.UserManager.exception.domain;

/** 400: a verification or reset token is invalid, expired, or already used. */
public class InvalidTokenException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public InvalidTokenException(String message) {
		super(message);
	}
}
