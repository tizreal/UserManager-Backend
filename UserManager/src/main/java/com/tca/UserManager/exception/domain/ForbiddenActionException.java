package com.tca.UserManager.exception.domain;

/** 403: authenticated, but not allowed to perform this action on this resource. */
public class ForbiddenActionException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public ForbiddenActionException(String message) {
		super(message);
	}
}
