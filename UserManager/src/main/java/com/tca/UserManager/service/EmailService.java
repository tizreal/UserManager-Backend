package com.tca.UserManager.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import com.tca.UserManager.entity.User;
import com.tca.UserManager.provider.ResourceProvider;
import com.tca.UserManager.security.JwtService;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {

	final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Value("${spring.mail.username}")
	private String emailFrom; // The Gmail address emails are sent from

	@Autowired
	JwtService jwtService;
	@Autowired
	ResourceProvider provider;
	@Autowired
	TemplateEngine templateEngine; // Thymeleaf for HTML templates
	@Autowired
	JavaMailSender javaMailSender; // Spring's mail sender

	// @Async means this runs on a background thread — the API responds immediately
	@Async
	public void sendVerificationEmail(User user) {
		this.sendEmail(user, this.provider.getClientVerifyParam(), // 'user/verifyEmail'
				"verify_email", // Template file name
				String.format("Welcome %s %s", user.getFirstName(), user.getLastName()),
				this.provider.getClientVerifyExpiration() // 24 hours
		);
	}

	@Async
	public void sendResetPasswordEmail(User user) {
		this.sendEmail(user, this.provider.getClientResetParam(), // 'user/resetPassword'
				"reset_password", "Reset your password", this.provider.getClientResetExpiration() // 10 minutes
		);
	}

	private void sendEmail(User user, String clientParam, String templateName, String subject, long expiration) {
		try {
			// 1. Build the Thymeleaf context — these become template variables
			Context context = new Context();
			context.setVariable("user", user);
			context.setVariable("client", this.provider.getClientUrl());
			context.setVariable("param", clientParam);
			// Generate a short-lived JWT for the email link:
			context.setVariable("token", this.jwtService.generateJwtToken(user.getUsername(), expiration));

			// 2. Process the HTML template with Thymeleaf
			String htmlContent = this.templateEngine.process(templateName, context);

			// 3. Build the MIME email message
			MimeMessage mimeMessage = this.javaMailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(mimeMessage);
			helper.setFrom(this.emailFrom, "TCA User Manager");
			helper.setSubject(subject);
			helper.setText(htmlContent, true); // true = HTML content
			helper.setTo(user.getEmail());

			// 4. Send
			this.javaMailSender.send(mimeMessage);
			logger.debug("Email sent to: {}", user.getEmail());

		} catch (Exception ex) {
			logger.error("Error sending email to: " + user.getEmail(), ex);
		}
	}

}