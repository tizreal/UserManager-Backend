package com.tca.UserManager.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.tca.UserManager.filter.CustomAuthEntryPoint;
import com.tca.UserManager.filter.JwtAuthorizationFilter;

/**
 * URL-level access rules. Paths are relative to the /api context path.
 * Admin-only operations are also guarded with @PreAuthorize on the controller methods,
 * and ownership rules (e.g. deleting a post) are enforced in the service layer.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class WebSecurityConfig {

	private final JwtAuthorizationFilter jwtAuthorizationFilter;
	private final CustomAuthEntryPoint authEntryPoint;
	private final HandlerExceptionResolver resolver;
	private final List<String> allowedOrigins;

	public WebSecurityConfig(JwtAuthorizationFilter jwtAuthorizationFilter, CustomAuthEntryPoint authEntryPoint,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
			@Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:3000,https://usermanager-frontend.vercel.app}") List<String> allowedOrigins) {
		this.jwtAuthorizationFilter = jwtAuthorizationFilter;
		this.authEntryPoint = authEntryPoint;
		this.resolver = resolver;
		this.allowedOrigins = allowedOrigins;
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		configuration.setExposedHeaders(List.of("Authorization"));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.cors(cors -> cors.configurationSource(corsConfigurationSource()))
			// Tokens travel in the Authorization header, not cookies, so CSRF does not apply.
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
				// Public: account creation and recovery, health, error page
				.requestMatchers(HttpMethod.POST, "/user/login", "/user/signup", "/user/reset").permitAll()
				.requestMatchers(HttpMethod.GET, "/user/reset/*", "/user/verify/email", "/health").permitAll()
				.requestMatchers("/error").permitAll()
				// Own account (must come before the admin patterns below)
				.requestMatchers(HttpMethod.GET, "/user/get").authenticated()
				.requestMatchers(HttpMethod.POST, "/user/update").authenticated()
				// Admin only: listing, looking up, and deleting other accounts
				.requestMatchers(HttpMethod.GET, "/user/", "/user/*").hasRole("ADMIN")
				.requestMatchers(HttpMethod.DELETE, "/user/*").hasRole("ADMIN")
				// Everything else (posts, comments) requires a logged-in user
				.anyRequest().authenticated())
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint(authEntryPoint)
				.accessDeniedHandler((req, res, e) -> resolver.resolveException(req, res, null, e)))
			.addFilterBefore(jwtAuthorizationFilter, UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
		return config.getAuthenticationManager();
	}
}
