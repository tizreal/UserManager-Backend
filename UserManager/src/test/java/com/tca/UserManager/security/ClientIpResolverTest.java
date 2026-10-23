package com.tca.UserManager.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import com.tca.UserManager.exception.domain.RateLimitedException;

@ExtendWith(OutputCaptureExtension.class)
class ClientIpResolverTest {

	private static final List<String> PRIVATE = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

	private static MockHttpServletRequest request(String remote, String header, String value) {
		MockHttpServletRequest r = new MockHttpServletRequest();
		r.setRemoteAddr(remote);
		if (header != null) {
			r.addHeader(header, value);
		}
		return r;
	}

	@Test
	void unknownModeNeverTrustsHeaders() {
		ClientIpResolver resolver = new ClientIpResolver(ClientIpResolver.Mode.UNKNOWN, "", PRIVATE, false);
		assertThat(resolver.resolve(request("10.1.2.3", "X-Forwarded-For", "203.0.113.9"))).isNull();
		assertThat(resolver.resolve(request("10.1.2.3", "CF-Connecting-IP", "203.0.113.9"))).isNull();
	}

	@Test
	void trustedHeaderIsUsedOnlyWhenTheConnectionComesFromATrustedProxy() {
		ClientIpResolver resolver =
				new ClientIpResolver(ClientIpResolver.Mode.TRUSTED_HEADER, "CF-Connecting-IP", PRIVATE, false);
		assertThat(resolver.resolve(request("10.1.2.3", "CF-Connecting-IP", "203.0.113.9"))).isEqualTo("203.0.113.9");
		// Direct connection from the internet with a forged header: ignored
		assertThat(resolver.resolve(request("198.51.100.7", "CF-Connecting-IP", "203.0.113.9"))).isNull();
		// Trusted peer, but no header
		assertThat(resolver.resolve(request("10.1.2.3", null, null))).isNull();
	}

	@Test
	void multiHopAndMalformedHeaderValuesAreRejected() {
		ClientIpResolver resolver =
				new ClientIpResolver(ClientIpResolver.Mode.TRUSTED_HEADER, "CF-Connecting-IP", PRIVATE, false);
		assertThat(resolver.resolve(request("10.1.2.3", "CF-Connecting-IP", "1.2.3.4, 203.0.113.9"))).isNull();
		assertThat(resolver.resolve(request("10.1.2.3", "CF-Connecting-IP", "999.1.1.1"))).isNull();
		assertThat(resolver.resolve(request("10.1.2.3", "CF-Connecting-IP", "evil.example.com"))).isNull();
		assertThat(resolver.resolve(request("10.1.2.3", "CF-Connecting-IP", "2001:DB8::1"))).isEqualTo("2001:db8::1");
	}

	@Test
	void remoteAddrModeUsesTheSocketAddressAndIgnoresHeaders() {
		ClientIpResolver resolver = new ClientIpResolver(ClientIpResolver.Mode.REMOTE_ADDR, "", PRIVATE, false);
		assertThat(resolver.resolve(request("203.0.113.9", "X-Forwarded-For", "1.2.3.4"))).isEqualTo("203.0.113.9");
	}

	@Test
	void trustedHeaderModeRequiresAHeaderName() {
		assertThatThrownBy(() -> new ClientIpResolver(ClientIpResolver.Mode.TRUSTED_HEADER, " ", PRIVATE, false))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void diagnosticsLogOnlySanitizedIpCharacters(CapturedOutput output) {
		ClientIpResolver resolver = new ClientIpResolver(ClientIpResolver.Mode.UNKNOWN, "", PRIVATE, true);
		resolver.resolve(request("10.1.2.3", "X-Forwarded-For", "1.2.3.4\nFAKE LOG LINE <script>"));
		assertThat(output.getAll()).contains("client-ip diagnostics: remoteAddr=10.1.2.3")
				.doesNotContain("FAKE LOG LINE").doesNotContain("<script>");
	}

	@Test
	void unknownClientsFallBackToTheGlobalLimit() {
		RateLimiter limiter = new RateLimiter(true);
		for (int i = 0; i < 50; i++) {
			limiter.checkClient(RateLimiter.Rule.SIGNUP_PER_IP, RateLimiter.Rule.SIGNUP_GLOBAL, null);
		}
		assertThatThrownBy(() -> limiter.checkClient(RateLimiter.Rule.SIGNUP_PER_IP, RateLimiter.Rule.SIGNUP_GLOBAL, null))
				.isInstanceOf(RateLimitedException.class);
		// A known client is limited separately
		limiter.checkClient(RateLimiter.Rule.SIGNUP_PER_IP, RateLimiter.Rule.SIGNUP_GLOBAL, "203.0.113.9");
	}
}
