package cloud.bamsongi.albammate.global.config;

import java.util.function.Consumer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointPolicyRegistry;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.session.SessionCookieConfigurer;

/** P0 서버 세션, CSRF와 공개·보호 HTTP 경계를 구성한다. */
@Configuration
@EnableConfigurationProperties(SecurityCookieProperties.class)
@Import({ApiEndpointPolicyRegistry.class, SessionCookieConfigurer.class})
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(
		HttpSecurity http,
		CsrfTokenRepository csrfTokenRepository,
		ApiEndpointPolicyRegistry endpointPolicyRegistry,
		ApiAuthenticationEntryPoint authenticationEntryPoint,
		ApiAccessDeniedHandler accessDeniedHandler)
		throws Exception {
		http.csrf(
			csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
				.requireCsrfProtectionMatcher(
					endpointPolicyRegistry
						.csrfProtectionRequestMatcher())
				.csrfTokenRequestHandler(
					new CsrfTokenRequestAttributeHandler()))
			.sessionManagement(
				session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
			.requestCache(requestCache -> requestCache.disable())
			.exceptionHandling(
				exceptionHandling -> exceptionHandling
					.authenticationEntryPoint(authenticationEntryPoint)
					.accessDeniedHandler(accessDeniedHandler))
			.authorizeHttpRequests(
				authorize -> authorize
					.requestMatchers(
						endpointPolicyRegistry.publicRequestMatcher())
					.permitAll()
					.requestMatchers(
						endpointPolicyRegistry
							.authenticatedRequestMatcher())
					.authenticated()
					.requestMatchers(
						endpointPolicyRegistry.knownEndpointPathMatcher())
					.permitAll()
					.requestMatchers(
						endpointPolicyRegistry
							.protectedFutureSubpathMatcher())
					.authenticated()
					.anyRequest()
					.permitAll())
			.formLogin(formLogin -> formLogin.disable())
			.httpBasic(httpBasic -> httpBasic.disable())
			.logout(logout -> logout.disable());
		return http.build();
	}

	@Bean
	CsrfTokenRepository csrfTokenRepository(SecurityCookieProperties properties) {
		CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
		repository.setCookieName("XSRF-TOKEN");
		repository.setHeaderName("X-XSRF-TOKEN");
		repository.setCookiePath("/");
		repository.setCookieCustomizer(cookieCustomizer(properties));
		return repository;
	}

	@Bean
	RequestMatcher publicAuthenticationRequestMatcher(
		ApiEndpointPolicyRegistry endpointPolicyRegistry) {
		return endpointPolicyRegistry.publicRequestMatcher();
	}

	@Bean
	ServletContextInitializer sessionCookieInitializer(
		SessionCookieConfigurer sessionCookieConfigurer) {
		return sessionCookieConfigurer::configureSessionCookie;
	}

	private Consumer<org.springframework.http.ResponseCookie.ResponseCookieBuilder> cookieCustomizer(
		SecurityCookieProperties properties) {
		return cookie -> cookie.path("/").httpOnly(true).sameSite("Lax").secure(properties.isSecure());
	}
}
