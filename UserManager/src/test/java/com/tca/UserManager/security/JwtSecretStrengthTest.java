package com.tca.UserManager.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.tca.UserManager.provider.ResourceProvider;

class JwtSecretStrengthTest {

	private static JwtService withSecret(String secret) {
		JwtService service = new JwtService();
		service.provider = mock(ResourceProvider.class);
		when(service.provider.getJwtSecret()).thenReturn(secret);
		return service;
	}

	@Test
	void missingOrShortSecretsPreventStartup() {
		assertThatThrownBy(() -> withSecret(null).checkSecretStrength()).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> withSecret("short-secret").checkSecretStrength())
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void longSecretsAreAccepted() {
		assertThatCode(() -> withSecret("x".repeat(64)).checkSecretStrength()).doesNotThrowAnyException();
	}
}
