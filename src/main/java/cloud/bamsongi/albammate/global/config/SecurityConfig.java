package cloud.bamsongi.albammate.global.config;

import cloud.bamsongi.albammate.global.security.ApiAccessDeniedHandler;
import cloud.bamsongi.albammate.global.security.ApiAuthenticationEntryPoint;
import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionCookieConfig;
import java.util.function.Consumer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** P0 서버 세션, CSRF와 공개·보호 HTTP 경계를 구성한다. */
@Configuration
@EnableConfigurationProperties(SecurityCookieProperties.class)
public class SecurityConfig {

    private static final String CSRF_PATH = "/api/auth/csrf";
    private static final String SIGNUP_PATH = "/api/auth/signup";
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String LOGOUT_PATH = "/api/auth/logout";
    private static final String CURRENT_USER_PATH = "/api/users/me";
    private static final String CURRENT_USER_ROOMS_PATH = "/api/users/me/rooms";
    private static final String GAMES_PATH = "/api/games";
    private static final String GAME_DETAIL_PATH = "/api/games/{gameId}";
    private static final String ROOMS_PATH = "/api/rooms";
    private static final String ROOM_DETAIL_PATH = "/api/rooms/{roomId}";
    private static final String ROOM_STATUS_PATH = "/api/rooms/{roomId}/status";
    private static final String ROOM_PARTICIPANTS_PATH = "/api/rooms/{roomId}/participants";
    private static final String ROOM_PARTICIPATION_ME_PATH = "/api/rooms/{roomId}/participants/me";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            RequestMatcher publicAuthenticationRequestMatcher,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        http.csrf(
                        csrf ->
                                csrf.csrfTokenRepository(csrfTokenRepository)
                                        .requireCsrfProtectionMatcher(
                                                csrfProtectionRequestMatcher())
                                        .csrfTokenRequestHandler(
                                                new CsrfTokenRequestAttributeHandler()))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(requestCache -> requestCache.disable())
                .exceptionHandling(
                        exceptionHandling ->
                                exceptionHandling
                                        .authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(HttpMethod.GET, CSRF_PATH)
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, GAMES_PATH)
                                        .permitAll()
                                        .requestMatchers(
                                                PathPatternRequestMatcher.pathPattern(
                                                        HttpMethod.GET, GAME_DETAIL_PATH))
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, ROOMS_PATH)
                                        .permitAll()
                                        .requestMatchers(
                                                PathPatternRequestMatcher.pathPattern(
                                                        HttpMethod.GET, ROOM_DETAIL_PATH))
                                        .permitAll()
                                        .requestMatchers(publicAuthenticationRequestMatcher)
                                        .permitAll()
                                        .requestMatchers(protectedP0RequestMatcher())
                                        .authenticated()
                                        .requestMatchers(p0EndpointPathMatcher())
                                        .permitAll()
                                        .requestMatchers(protectedFutureSubpathMatcher())
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }

    private RequestMatcher protectedP0RequestMatcher() {
        return new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LOGOUT_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, CURRENT_USER_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.HEAD, CURRENT_USER_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.PATCH, CURRENT_USER_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, ROOMS_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.PATCH, ROOM_DETAIL_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, ROOM_DETAIL_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.PATCH, ROOM_STATUS_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, ROOM_PARTICIPANTS_PATH),
                PathPatternRequestMatcher.pathPattern(
                        HttpMethod.DELETE, ROOM_PARTICIPATION_ME_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, CURRENT_USER_ROOMS_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.HEAD, CURRENT_USER_ROOMS_PATH));
    }

    private RequestMatcher p0EndpointPathMatcher() {
        return new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(CSRF_PATH),
                PathPatternRequestMatcher.pathPattern(SIGNUP_PATH),
                PathPatternRequestMatcher.pathPattern(LOGIN_PATH),
                PathPatternRequestMatcher.pathPattern(LOGOUT_PATH),
                PathPatternRequestMatcher.pathPattern(CURRENT_USER_PATH),
                PathPatternRequestMatcher.pathPattern(CURRENT_USER_ROOMS_PATH),
                PathPatternRequestMatcher.pathPattern(GAMES_PATH),
                PathPatternRequestMatcher.pathPattern(GAME_DETAIL_PATH),
                PathPatternRequestMatcher.pathPattern(ROOMS_PATH),
                PathPatternRequestMatcher.pathPattern(ROOM_DETAIL_PATH),
                PathPatternRequestMatcher.pathPattern(ROOM_STATUS_PATH),
                PathPatternRequestMatcher.pathPattern(ROOM_PARTICIPANTS_PATH),
                PathPatternRequestMatcher.pathPattern(ROOM_PARTICIPATION_ME_PATH));
    }

    private RequestMatcher protectedFutureSubpathMatcher() {
        return new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern("/api/auth/*/**"),
                PathPatternRequestMatcher.pathPattern("/api/games/*/**"),
                PathPatternRequestMatcher.pathPattern("/api/rooms/*/**"),
                PathPatternRequestMatcher.pathPattern("/api/users/me/*/**"));
    }

    private RequestMatcher csrfProtectionRequestMatcher() {
        return new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, SIGNUP_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LOGIN_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LOGOUT_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.PATCH, CURRENT_USER_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, ROOMS_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.PATCH, ROOM_DETAIL_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, ROOM_DETAIL_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.PATCH, ROOM_STATUS_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, ROOM_PARTICIPANTS_PATH),
                PathPatternRequestMatcher.pathPattern(
                        HttpMethod.DELETE, ROOM_PARTICIPATION_ME_PATH),
                stateChangingFutureSubpathMatcher());
    }

    private RequestMatcher stateChangingFutureSubpathMatcher() {
        return new AndRequestMatcher(
                protectedFutureSubpathMatcher(),
                new NegatedRequestMatcher(p0EndpointPathMatcher()),
                new OrRequestMatcher(
                        PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/**"),
                        PathPatternRequestMatcher.pathPattern(HttpMethod.PATCH, "/**"),
                        PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/**"),
                        PathPatternRequestMatcher.pathPattern(HttpMethod.PUT, "/**")));
    }

    @Bean
    RequestMatcher publicAuthenticationRequestMatcher() {
        return new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, SIGNUP_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LOGIN_PATH));
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
    ServletContextInitializer sessionCookieInitializer(SecurityCookieProperties properties) {
        return (ServletContext servletContext) ->
                configureSessionCookie(servletContext, properties);
    }

    private Consumer<org.springframework.http.ResponseCookie.ResponseCookieBuilder>
            cookieCustomizer(SecurityCookieProperties properties) {
        return cookie ->
                cookie.path("/").httpOnly(true).sameSite("Lax").secure(properties.isSecure());
    }

    private void configureSessionCookie(
            ServletContext servletContext, SecurityCookieProperties properties) {
        SessionCookieConfig cookieConfig = servletContext.getSessionCookieConfig();
        cookieConfig.setName("JSESSIONID");
        cookieConfig.setPath("/");
        cookieConfig.setHttpOnly(true);
        cookieConfig.setSecure(properties.isSecure());
        cookieConfig.setAttribute("SameSite", "Lax");
    }
}
