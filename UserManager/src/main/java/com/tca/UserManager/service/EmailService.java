package com.tca.UserManager.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.provider.ResourceProvider;

@Service
public class EmailService {

	final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Value("${resend.api.key}")
	private String resendApiKey;

	@Value("${resend.from.email}")
	private String emailFrom; // e.g. "TCA User Manager <onboarding@resend.dev>"

	@Autowired
	ResourceProvider provider;
	@Autowired
	TemplateEngine templateEngine;

	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final ObjectMapper objectMapper = new ObjectMapper();

	// Tokens are created by the caller (UserService / PasswordResetService) and are
	// only placed in the email body. They are never logged.
	@Async
	public void sendVerificationEmail(User user, String recipientEmail, String token) {
		this.sendEmail(user, recipientEmail, this.provider.getClientVerifyParam(),
				"verify_email",
				String.format("Welcome %s %s", user.getFirstName(), user.getLastName()),
				token
		);
	}

	@Async
	public void sendResetPasswordEmail(User user, String token) {
		this.sendEmail(user, user.getEmail(), this.provider.getClientResetParam(),
				"reset_password", "Reset your password", token
		);
	}

	private void sendEmail(User user, String recipientEmail, String clientParam, String templateName, String subject,
			String token) {
		try {
			// 1. Build the Thymeleaf context
			Context context = new Context();
			context.setVariable("user", user);
			context.setVariable("client", this.provider.getClientUrl());
			context.setVariable("param", clientParam);
			context.setVariable("token", token);

			// 2. Process the HTML template
			String htmlContent = this.templateEngine.process(templateName, context);

			// 3. Build the JSON payload for Resend's API
			var payload = objectMapper.createObjectNode();
			payload.put("from", this.emailFrom);
			payload.putArray("to").add(recipientEmail);
			payload.put("subject", subject);
			payload.put("html", htmlContent);

			String body = objectMapper.writeValueAsString(payload);

			// 4. Send via Resend's HTTPS API
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("https://api.resend.com/emails"))
					.header("Authorization", "Bearer " + this.resendApiKey)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				logger.debug("Sent {} email", templateName);
			} else {
				logger.error("Resend API error ({}) sending {} email", response.statusCode(), templateName);
			}

		} catch (Exception ex) {
			logger.error("Error sending {} email: {}", templateName, ex.getClass().getSimpleName());
		}
	}
}