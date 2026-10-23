package com.tca.UserManager.exception.domain;

/** 400: the current password supplied to confirm a sensitive change is wrong. */
public class IncorrectPasswordException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public IncorrectPasswordException() {
		super("Current password is incorrect");
	}
}
