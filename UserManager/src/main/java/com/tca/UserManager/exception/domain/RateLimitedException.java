package com.tca.UserManager.exception.domain;

/** 429: too many attempts; retry after the given number of seconds. */
public class RateLimitedException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final long retryAfterSeconds;

	public RateLimitedException(long retryAfterSeconds) {
		super("Too many attempts. Please try again later.");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
