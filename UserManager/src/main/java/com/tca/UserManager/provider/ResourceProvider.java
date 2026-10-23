package com.tca.UserManager.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;
import com.tca.UserManager.provider.factory.YamlPropertySourceFactory;

@Component
@PropertySource(value = "classpath:config.yml", factory = YamlPropertySourceFactory.class)
public class ResourceProvider {

	@Value("${app.security.jwt.secret}")
	private String jwtSecret;

	@Value("${app.security.jwt.expiration}")
	private long jwtExpiration;

	@Value("${app.security.jwt.issuer}")
	private String jwtIssuer;

	@Value("${app.security.jwt.audience}")
	private String jwtAudience;



	@Value("${client.url}")
	private String clientUrl;

	@Value("${client.email.verify.param}")

	private String clientVerifyParam;

	@Value("${client.email.verify.expiration}")
	private long clientVerifyExpiration;

	@Value("${client.email.reset.param}")
	private String clientResetParam;

	@Value("${client.email.reset.expiration}")
	private long clientResetExpiration;



	// Getters
	public String getJwtSecret() {
		return jwtSecret;
	}

	public long getJwtExpiration() {
		return jwtExpiration;
	}

	public String getJwtIssuer() {
		return jwtIssuer;
	}

	public String getJwtAudience() {
		return jwtAudience;
	}



	public String getClientUrl() {
		return clientUrl;
	}

	public String getClientVerifyParam() {
		return clientVerifyParam;
	}

	public long getClientVerifyExpiration() {
		return clientVerifyExpiration;
	}

	public String getClientResetParam() {
		return clientResetParam;
	}

	public long getClientResetExpiration() {
		return clientResetExpiration;
	}


}