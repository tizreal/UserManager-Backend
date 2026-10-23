package com.tca.UserManager.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.tca.UserManager.exception.domain.RateLimitedException;

/**
 * In-memory fixed-window rate limiter.
 *
 * Each rule allows {@code limit} attempts per {@code window} for a key (an IP address,
 * an account identifier, or a user id). It is per-instance memory: correct for the
 * single Render instance this app runs on; multiple instances would need a shared
 * store (e.g. Redis or a database table) instead.
 *
 * Limits are configurable (app.rate-limit.*) and can be switched off for local
 * development with app.rate-limit.enabled=false.
 */
@Component
public class RateLimiter {

	public enum Rule {
		LOGIN_PER_IP(30, Duration.ofMinutes(15)),
		LOGIN_GLOBAL(600, Duration.ofMinutes(15)),
		LOGIN_PER_ACCOUNT(10, Duration.ofMinutes(15)),
		SIGNUP_PER_IP(5, Duration.ofHours(1)),
		SIGNUP_GLOBAL(50, Duration.ofHours(1)),
		RESET_REQUEST_PER_IP(10, Duration.ofHours(1)),
		RESET_REQUEST_GLOBAL(100, Duration.ofHours(1)),
		RESET_REQUEST_PER_ACCOUNT(3, Duration.ofHours(1)),
		RESET_SUBMIT_PER_IP(10, Duration.ofHours(1)),
		RESET_SUBMIT_GLOBAL(100, Duration.ofHours(1)),
		VERIFY_EMAIL_PER_IP(20, Duration.ofHours(1)),
		VERIFY_EMAIL_GLOBAL(300, Duration.ofHours(1)),
		CURRENT_PASSWORD_PER_USER(10, Duration.ofMinutes(15)),
		POST_PER_USER(20, Duration.ofHours(1)),
		COMMENT_PER_USER(60, Duration.ofHours(1)),
		LIKE_PER_USER(300, Duration.ofHours(1));

		final int limit;
		final Duration window;

		Rule(int limit, Duration window) {
			this.limit = limit;
			this.window = window;
		}
	}

	private record Window(long startMillis, int count) {
	}

	private final Map<String, Window> windows = new ConcurrentHashMap<>();
	private final boolean enabled;
	private Clock clock = Clock.systemUTC();

	public RateLimiter(@Value("${app.rate-limit.enabled:true}") boolean enabled) {
		this.enabled = enabled;
	}

	/** Records an attempt; throws RateLimitedException (429) once the window's limit is exceeded. */
	public void check(Rule rule, String key) {
		if (!enabled || key == null) {
			return;
		}
		long now = clock.millis();
		long windowMillis = rule.window.toMillis();
		String mapKey = rule.name() + ':' + key.toLowerCase(Locale.ROOT);

		Window updated = windows.compute(mapKey, (k, w) -> (w == null || now - w.startMillis() >= windowMillis)
				? new Window(now, 1)
				: new Window(w.startMillis(), w.count() + 1));

		if (updated.count() > rule.limit) {
			long retryAfterSeconds = Math.max(1, (updated.startMillis() + windowMillis - now + 999) / 1000);
			throw new RateLimitedException(retryAfterSeconds);
		}
		if (windows.size() > 50_000) {
			windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= Duration.ofHours(1).toMillis());
		}
	}

	/**
	 * Per-client limit when the client IP is known; otherwise (null, see ClientIpResolver)
	 * a coarse global limit, so spoofable headers never decide who gets throttled.
	 */
	public void checkClient(Rule perIp, Rule global, String clientIpOrNull) {
		if (clientIpOrNull != null) {
			check(perIp, clientIpOrNull);
		} else {
			check(global, "all-clients");
		}
	}

	/** For tests. */
	public void reset() {
		windows.clear();
	}

	/** For tests. */
	void setClock(Clock clock) {
		this.clock = clock;
	}
}
