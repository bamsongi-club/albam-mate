package cloud.bamsongi.albammate.global.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.CookieSerializer;

import cloud.bamsongi.albammate.global.config.SecurityCookieProperties;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.infra.redis.RedisSessionConfiguration;

class SessionConfigurationTest {

	private final SessionConfiguration configuration = new SessionConfiguration();

	@Test
	void local_test_postgresTest_저장소는_30분_인메모리_세션을_사용한다() {
		MapSessionRepository repository = new SessionConfiguration.InMemorySessionRepositoryConfiguration()
			.sessionRepository();

		MapSession session = repository.createSession();

		assertEquals(Duration.ofMinutes(30), session.getMaxInactiveInterval());
		assertEquals(SessionConfiguration.SESSION_TTL, session.getMaxInactiveInterval());
		session.setLastAccessedTime(Instant.now().minus(SessionConfiguration.SESSION_TTL.plusSeconds(1)));
		repository.save(session);

		assertNull(repository.findById(session.getId()));
	}

	@Test
	void local_multi는_Redis_세션만_선택하고_인메모리_fallback을_등록하지_않는다() {
		Profile redisProfile = AnnotatedElementUtils.findMergedAnnotation(
			RedisSessionConfiguration.class, Profile.class);
		Profile inMemoryProfile = AnnotatedElementUtils.findMergedAnnotation(
			SessionConfiguration.InMemorySessionRepositoryConfiguration.class,
			Profile.class);
		EnableRedisHttpSession localMultiSession = AnnotatedElementUtils.findMergedAnnotation(
			nestedRedisConfiguration("LocalMultiSessionRepositoryConfiguration"),
			EnableRedisHttpSession.class);
		assertEquals("local-multi", redisProfile.value()[0]);
		assertEquals("!local-multi", inMemoryProfile.value()[0]);
		assertEquals("albam-mate:local-multi:session", localMultiSession.redisNamespace());
		assertEquals(Duration.ofMinutes(30).toSeconds(), localMultiSession.maxInactiveIntervalInSeconds());
		assertTrue(AnnotatedElementUtils.hasAnnotation(
			SessionConfiguration.InMemorySessionRepositoryConfiguration.class, EnableSpringHttpSession.class));
		assertFalse(AnnotatedElementUtils.hasAnnotation(
			nestedRedisConfiguration("LocalMultiSessionRepositoryConfiguration"), EnableSpringHttpSession.class));
	}

	@Test
	void Redis_연결은_프로필_외부_설정값을_사용한다() {
		try (AnnotationConfigApplicationContext context = redisSessionContext()) {
			LettuceConnectionFactory connectionFactory = context.getBean(LettuceConnectionFactory.class);

			assertEquals("redis", connectionFactory.getHostName());
			assertEquals(6379, connectionFactory.getPort());
		}
	}

	@Test
	void Spring_Session_쿠키는_기존_JSESSIONID_계약을_그대로_사용한다() {
		SecurityCookieProperties properties = new SecurityCookieProperties();
		properties.setSecure(false);
		CookieSerializer serializer = configuration.cookieSerializer(properties);
		MockHttpServletResponse response = new MockHttpServletResponse();

		serializer.writeCookieValue(
			new CookieSerializer.CookieValue(new MockHttpServletRequest(), response, "session-id"));

		String setCookie = response.getHeader("Set-Cookie");
		assertTrue(setCookie.contains("JSESSIONID=session-id"), setCookie);
		assertTrue(setCookie.contains("Path=/"));
		assertTrue(setCookie.contains("HttpOnly"));
		assertTrue(setCookie.contains("SameSite=Lax"));
		assertFalse(setCookie.contains("Secure"));
	}

	@Test
	void Redis_세션_JSON은_Security_인증과_현재_사용자_주체를_복원한다() {
		try (AnnotationConfigApplicationContext context = redisSessionContext()) {
			RedisSerializer<Object> serializer = context.getBean(
				"springSessionDefaultRedisSerializer", RedisSerializer.class);
			CurrentUserPrincipal principal = new CurrentUserPrincipal(42L);
			SecurityContextImpl securityContext = new SecurityContextImpl(
				new UsernamePasswordAuthenticationToken(principal, null, AuthorityUtils.NO_AUTHORITIES));

			SecurityContextImpl restored = assertInstanceOf(
				SecurityContextImpl.class, serializer.deserialize(serializer.serialize(securityContext)));

			UsernamePasswordAuthenticationToken authentication = assertInstanceOf(
				UsernamePasswordAuthenticationToken.class, restored.getAuthentication());
			assertEquals(principal, authentication.getPrincipal());
		}
	}

	private AnnotationConfigApplicationContext redisSessionContext() {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().setActiveProfiles("local-multi");
		TestPropertyValues.of("app.redis.host=redis", "app.redis.port=6379").applyTo(context);
		context.register(RedisSessionConfiguration.class);
		context.refresh();
		return context;
	}

	private Class<?> nestedRedisConfiguration(String simpleName) {
		return Arrays.stream(RedisSessionConfiguration.class.getDeclaredClasses())
			.filter(candidate -> candidate.getSimpleName().equals(simpleName))
			.findFirst()
			.orElseThrow();
	}
}
