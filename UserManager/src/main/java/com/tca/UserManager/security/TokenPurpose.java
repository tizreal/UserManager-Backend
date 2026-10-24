package com.tca.UserManager.security;

/**
 * Every JWT carries a "pur" claim, and each endpoint accepts exactly one purpose,
 * so a token issued for one job cannot be replayed for another (e.g. an email
 * verification link cannot be used as a login session).
 *
 * Password reset intentionally does NOT use a JWT: it uses single-use opaque tokens
 * stored (hashed) in the database. See PasswordResetService.
 */
public enum TokenPurpose {
	ACCESS("access"),
	EMAIL_VERIFICATION("verify-email");

	private final String claim;

	TokenPurpose(String claim) {
		this.claim = claim;
	}

	public String claim() {
		return claim;
	}
}
