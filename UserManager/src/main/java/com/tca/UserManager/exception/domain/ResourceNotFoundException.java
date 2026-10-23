package com.tca.UserManager.exception.domain;

/** 404: the requested post, comment, or user does not exist. */
public class ResourceNotFoundException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public ResourceNotFoundException(String message) {
		super(message);
	}
}
