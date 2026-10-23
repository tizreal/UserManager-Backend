package com.tca.UserManager.exception.domain;

/** 409: the request conflicts with the current state (e.g. deleting the last admin). */
public class ConflictException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public ConflictException(String message) {
		super(message);
	}
}
