package com.tca.UserManager.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.tca.UserManager.exception.domain.RateLimitedException;

class RateLimiterTest {

	private static Clock at(Instant instant) {
		return Clock.fixed(instant, ZoneOffset.UTC);
	}

	@Test
	void allowsUpToTheLimitThenBlocksUntilTheWindowEnds() {
		RateLimiter limiter = new RateLimiter(true);
		Instant start = Instant.parse("2026-01-01T00:00:00Z");
		limiter.setClock(at(start));
		for (int i = 0; i < 10; i++) {
			limiter.check(RateLimiter.Rule.LOGIN_PER_ACCOUNT, "alice");
		}
		assertThatThrownBy(() -> limiter.check(RateLimiter.Rule.LOGIN_PER_ACCOUNT, "alice"))
				.isInstanceOf(RateLimitedException.class);

		limiter.setClock(at(start.plus(Duration.ofMinutes(15))));
		assertThatCode(() -> limiter.check(RateLimiter.Rule.LOGIN_PER_ACCOUNT, "alice")).doesNotThrowAnyException();
	}

	@Test
	void keysAreIndependentAndCaseInsensitive() {
		RateLimiter limiter = new RateLimiter(true);
		for (int i = 0; i < 10; i++) {
			limiter.check(RateLimiter.Rule.LOGIN_PER_ACCOUNT, i % 2 == 0 ? "alice" : "ALICE");
		}
		assertThatThrownBy(() -> limiter.check(RateLimiter.Rule.LOGIN_PER_ACCOUNT, "Alice"))
				.isInstanceOf(RateLimitedException.class);
		assertThatCode(() -> limiter.check(RateLimiter.Rule.LOGIN_PER_ACCOUNT, "bob")).doesNotThrowAnyException();
	}

	@Test
	void canBeDisabled() {
		RateLimiter limiter = new RateLimiter(false);
		for (int i = 0; i < 100; i++) {
			limiter.check(RateLimiter.Rule.SIGNUP_PER_IP, "127.0.0.1");
		}
	}
}
