package com.tca.UserManager.entity;

/**
 * ACTIVE: normal account. DISABLED: deactivated by an admin; reversible; content and
 * attribution preserved; cannot authenticate. DELETED: personal data anonymized;
 * irreversible; cannot authenticate.
 */
public enum AccountStatus {
	ACTIVE,
	DISABLED,
	DELETED
}
