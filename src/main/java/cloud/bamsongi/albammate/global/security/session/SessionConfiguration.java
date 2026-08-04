package cloud.bamsongi.albammate.global.security.session;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import cloud.bamsongi.albammate.global.config.SecurityCookieProperties;

/** 모든 프로필이 공유하는 Spring Session 쿠키와 인메모리 저장소 경계다. */
@Configuration(proxyBeanMethods = false)
@Import(SessionConfiguration.InMemorySessionRepositoryConfiguration.class)
public class SessionConfiguration {

	static final Duration SESSION_TTL = Duration.ofMinutes(30);

	@Bean
	CookieSerializer cookieSerializer(SecurityCookieProperties properties) {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		serializer.setCookieName(SessionCookieConfigurer.SESSION_COOKIE_NAME);
		serializer.setCookiePath(SessionCookieConfigurer.COOKIE_PATH);
		serializer.setUseHttpOnlyCookie(true);
		serializer.setUseSecureCookie(properties.isSecure());
		serializer.setSameSite(SessionCookieConfigurer.SAME_SITE);
		serializer.setUseBase64Encoding(false);
		return serializer;
	}

	@Configuration(proxyBeanMethods = false)
	@Profile("!local-multi")
	@EnableSpringHttpSession
	static class InMemorySessionRepositoryConfiguration {

		@Bean
		MapSessionRepository sessionRepository() {
			MapSessionRepository repository = new MapSessionRepository(new ConcurrentHashMap<>());
			repository.setDefaultMaxInactiveInterval(SESSION_TTL);
			return repository;
		}
	}
}
