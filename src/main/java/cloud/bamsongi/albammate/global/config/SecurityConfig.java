package cloud.bamsongi.albammate.global.config;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointPolicyRegistry;
import cloud.bamsongi.albammate.global.security.error.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.error.ApiAuthenticationEntryPoint;
import cloud.bamsongi.albammate.global.security.session.SessionConfiguration;
import cloud.bamsongi.albammate.global.security.session.SessionCookieConfigurer;

/** P0 서버 세션, CSRF와 공개·보호 HTTP 경계를 구성한다. */
@Configuration
@EnableConfigurationProperties(SecurityCookieProperties.class)
@Import({
	ApiEndpointPolicyRegistry.class,
	SessionConfiguration.class,
	SessionCookieConfigurer.class
})
public class SecurityConfig {

	/**
	 * AUTH-05 OAuth 경로다. 값의 정본은 {@code auth.social}의 client 등록부이며 여기서는 filter 경계만 고정한다.
	 *
	 * <p>이 두 경로는 JSON MVC API가 아니라 Spring Security filter가 처리하므로 {@link ApiEndpointPolicyRegistry}의
	 * MVC 대조 대상이 아니다. 그래서 보호 하위 경로 안전망보다 먼저 정확한 matcher로 공개한다.
	 */
	private static final String SOCIAL_AUTHORIZATION_BASE_URI = "/api/auth/social/authorization";
	private static final String SOCIAL_CALLBACK_BASE_URI = "/api/auth/social/callback";

	/**
	 * 공용 필터 체인을 만든다.
	 *
	 * <p>{@code httpSecurityCustomizers}는 업무 모듈이 자기 인증 흐름을 이 체인에 더하는 확장점이다. AUTH-05의
	 * {@code oauth2Login}처럼 업무 모듈이 소유하는 handler와 filter를 {@code global}이 직접 참조하지 않고 받기 위한
	 * 것이며, 등록 순서에 의존하는 설정은 넣지 않는다. 위 {@code authorizeHttpRequests}에서 이미 판정하는 경로 정책은
	 * 확장점에서 다시 바꾸지 않는다.
	 */
	@Bean
	SecurityFilterChain securityFilterChain(
		HttpSecurity http,
		CsrfTokenRepository csrfTokenRepository,
		ApiEndpointPolicyRegistry endpointPolicyRegistry,
		ApiAuthenticationEntryPoint authenticationEntryPoint,
		ApiAccessDeniedHandler accessDeniedHandler,
		List<Customizer<HttpSecurity>> httpSecurityCustomizers)
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
						SOCIAL_AUTHORIZATION_BASE_URI + "/*",
						SOCIAL_CALLBACK_BASE_URI + "/*")
					.permitAll()
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
		httpSecurityCustomizers.forEach(customizer -> customizer.customize(http));
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

	/**
	 * 로그인 컨트롤러가 인증을 세션에 저장할 때 사용하는 저장소다.
	 *
	 * <p>필터 체인의 기본 저장소와 같은 세션 속성을 쓰므로, 다음 요청의 {@code SecurityContextHolderFilter}가 여기서
	 * 저장한 인증을 그대로 읽는다.
	 */
	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
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
