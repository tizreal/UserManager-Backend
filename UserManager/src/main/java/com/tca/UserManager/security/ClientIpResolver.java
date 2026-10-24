package com.tca.UserManager.security;

import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Determines the client IP for rate limiting without trusting spoofable input.
 *
 * Modes (app.client-ip.mode / CLIENT_IP_MODE):
 * <ul>
 * <li>UNKNOWN (default): behind a proxy whose forwarding headers have not been verified.
 * No header is trusted; resolve() returns null and callers fall back to global limits.</li>
 * <li>TRUSTED_HEADER: trust exactly one single-value header (CLIENT_IP_HEADER, e.g.
 * CF-Connecting-IP), and only when the TCP peer is inside CLIENT_IP_TRUSTED_PROXIES. A
 * comma-separated (multi-hop) or malformed value is rejected.</li>
 * <li>REMOTE_ADDR: no proxy in front (local development, tests); use the socket address.</li>
 * </ul>
 * X-Forwarded-For is deliberately not used: Render appends to a client-supplied value
 * rather than replacing it, so its contents can be forged.
 *
 * With CLIENT_IP_DIAGNOSTICS=true, every request logs the candidate headers (sanitized
 * IP-like characters only) so the correct header can be verified during a closed
 * maintenance window. Leave it off otherwise.
 */
@Component
public class ClientIpResolver {

	public enum Mode {
		UNKNOWN,
		TRUSTED_HEADER,
		REMOTE_ADDR
	}

	private static final Logger logger = LoggerFactory.getLogger(ClientIpResolver.class);
	private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})(\\.\\d{1,3}){3}$");
	private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]{2,39}$");
	private static final List<String> DIAGNOSTIC_HEADERS =
			List.of("X-Forwarded-For", "CF-Connecting-IP", "True-Client-IP", "X-Real-IP");

	private final Mode mode;
	private final String header;
	private final List<IpAddressMatcher> trustedProxies;
	private final boolean diagnostics;

	public ClientIpResolver(@Value("${app.client-ip.mode:UNKNOWN}") Mode mode,
			@Value("${app.client-ip.header:}") String header,
			@Value("${app.client-ip.trusted-proxies:10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,127.0.0.0/8,::1/128,fc00::/7}") List<String> trustedProxies,
			@Value("${app.client-ip.diagnostics:false}") boolean diagnostics) {
		this.mode = mode;
		this.header = header == null ? "" : header.trim();
		this.trustedProxies = trustedProxies.stream().map(String::trim).filter(s -> !s.isEmpty())
				.map(IpAddressMatcher::new).toList();
		this.diagnostics = diagnostics;
		if (mode == Mode.TRUSTED_HEADER && this.header.isEmpty()) {
			throw new IllegalStateException("CLIENT_IP_MODE=TRUSTED_HEADER requires CLIENT_IP_HEADER");
		}
	}

	/** The client IP, or null if it can't be determined without trusting spoofable data. */
	public String resolve(HttpServletRequest request) {
		String remote = request.getRemoteAddr();
		if (diagnostics) {
			logDiagnostics(request, remote);
		}
		return switch (mode) {
			case REMOTE_ADDR -> remote;
			case UNKNOWN -> null;
			case TRUSTED_HEADER -> isTrustedProxy(remote) ? validIp(request.getHeader(header)) : null;
		};
	}

	private boolean isTrustedProxy(String remote) {
		if (remote == null) {
			return false;
		}
		for (IpAddressMatcher matcher : trustedProxies) {
			try {
				if (matcher.matches(remote)) {
					return true;
				}
			} catch (IllegalArgumentException ignored) {
				// address family mismatch: not this range
			}
		}
		return false;
	}

	static String validIp(String value) {
		if (value == null) {
			return null;
		}
		String v = value.trim();
		if (IPV4.matcher(v).matches()) {
			for (String part : v.split("\\.")) {
				if (Integer.parseInt(part) > 255) {
					return null;
				}
			}
			return v;
		}
		return IPV6.matcher(v).matches() && v.contains(":") ? v.toLowerCase() : null;
	}

	private void logDiagnostics(HttpServletRequest request, String remote) {
		StringBuilder sb = new StringBuilder("client-ip diagnostics: remoteAddr=").append(sanitize(remote));
		for (String h : DIAGNOSTIC_HEADERS) {
			sb.append(' ').append(h).append('=').append(sanitize(request.getHeader(h)));
		}
		logger.info(sb.toString());
	}

	/** Keeps only characters that can appear in IP lists, so header values can't inject log content. */
	private static String sanitize(String value) {
		if (value == null) {
			return "-";
		}
		String cleaned = value.replaceAll("[^0-9a-fA-F:., ]", "");
		return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
	}
}
