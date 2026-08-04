package cloud.bamsongi.albammate.auth.social;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import jakarta.servlet.Filter;

/** AUTH-05 OAuth 로그인을 공용 SecurityFilterChain에 더한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SocialOAuthProperties.class)
public class SocialOAuthConfiguration {

	/**
	 * Spring Security가 생성하는 기본 로그인 페이지를 만들지 않기 위한 값이다.
	 *
	 * <p>인증되지 않은 API 요청은 공용 체인의 JSON entry point가 처리하므로 이 값으로 리다이렉트하지 않는다.
	 */
	private static final String LOGIN_PAGE = "/#/auth";

	@Bean
	OAuth2UserService<OidcUserRequest, OidcUser> socialOidcUserService() {
		return new SocialOidcUserService();
	}

	@Bean
	OAuth2AuthorizedClientRepository socialAuthorizedClientRepository() {
		return new DiscardingOAuth2AuthorizedClientRepository();
	}

	@Bean
	Filter socialProviderAvailabilityFilter(
		SocialClientRegistrationRepository clientRegistrationRepository) {
		return new SocialProviderAvailabilityFilter(clientRegistrationRepository);
	}

	@Bean
	Filter socialLinkCurrentUserFilter(CurrentUserAccessor currentUserAccessor) {
		return new SocialLinkCurrentUserFilter(currentUserAccessor);
	}

	/** 위 filter도 callback 경로에서만 의미가 있어 서블릿 컨테이너에 자동 등록하지 않는다. */
	@Bean
	FilterRegistrationBean<Filter> socialLinkCurrentUserFilterRegistration(
		@Qualifier("socialLinkCurrentUserFilter") Filter socialLinkCurrentUserFilter) {
		FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(
			socialLinkCurrentUserFilter);
		registration.setEnabled(false);
		return registration;
	}

	/**
	 * 위 filter를 서블릿 컨테이너에 자동 등록하지 않는다.
	 *
	 * <p>이 판정은 OAuth authorization·callback 경로에서만 의미가 있어 SecurityFilterChain 안에서만 실행한다.
	 */
	@Bean
	FilterRegistrationBean<Filter> socialProviderAvailabilityFilterRegistration(
		@Qualifier("socialProviderAvailabilityFilter") Filter socialProviderAvailabilityFilter) {
		FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(
			socialProviderAvailabilityFilter);
		registration.setEnabled(false);
		return registration;
	}

	/**
	 * authorization·callback 경로를 Spring Security filter에 연결한다.
	 *
	 * <p>공개 경로 정책은 공용 체인이 이미 판정하며, 여기서는 OAuth 흐름과 제공자 판정 filter만 더한다.
	 */
	@Bean
	Customizer<HttpSecurity> socialLoginSecurityCustomizer(
		OAuth2AuthorizedClientRepository authorizedClientRepository,
		SocialLinkAuthorizationRequestRepository socialLinkAuthorizationRequestRepository,
		SocialLoginSuccessHandler socialLoginSuccessHandler,
		SocialLoginFailureHandler socialLoginFailureHandler,
		@Qualifier("socialProviderAvailabilityFilter") Filter socialProviderAvailabilityFilter,
		@Qualifier("socialLinkCurrentUserFilter") Filter socialLinkCurrentUserFilter) {
		return http -> http
			.oauth2Login(
				oauth2Login -> oauth2Login.loginPage(LOGIN_PAGE)
					.authorizationEndpoint(
						endpoint -> endpoint.baseUri(
							SocialClientRegistrationRepository.AUTHORIZATION_BASE_URI)
							.authorizationRequestRepository(socialLinkAuthorizationRequestRepository))
					.redirectionEndpoint(
						endpoint -> endpoint.baseUri(
							SocialClientRegistrationRepository.CALLBACK_BASE_URI + "/*"))
					.authorizedClientRepository(authorizedClientRepository)
					.successHandler(socialLoginSuccessHandler)
					.failureHandler(socialLoginFailureHandler))
			.addFilterBefore(
				socialProviderAvailabilityFilter, OAuth2AuthorizationRequestRedirectFilter.class)
			.addFilterBefore(socialLinkCurrentUserFilter, OAuth2LoginAuthenticationFilter.class);
	}
}
