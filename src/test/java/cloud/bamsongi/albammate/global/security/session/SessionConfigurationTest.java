package cloud.bamsongi.albammate.global.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

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
	void T2_local과_production은_Redis_세션만_선택하고_인메모리_fallback을_등록하지_않는다() {
		Profile redisProfile = AnnotatedElementUtils.findMergedAnnotation(
			RedisSessionConfiguration.class, Profile.class);
		Profile inMemoryProfile = AnnotatedElementUtils.findMergedAnnotation(
			SessionConfiguration.InMemorySessionRepositoryConfiguration.class,
			Profile.class);
		EnableRedisHttpSession localMultiSession = AnnotatedElementUtils.findMergedAnnotation(
			nestedRedisConfiguration("LocalSessionRepositoryConfiguration"),
			EnableRedisHttpSession.class);
		EnableRedisHttpSession productionSession = AnnotatedElementUtils.findMergedAnnotation(
			nestedRedisConfiguration("ProductionSessionRepositoryConfiguration"),
			EnableRedisHttpSession.class);
		assertEquals(List.of("local", "production"), Arrays.asList(redisProfile.value()));
		assertEquals("!local & !production", inMemoryProfile.value()[0]);
		assertEquals("albam-mate:local:session", localMultiSession.redisNamespace());
		assertEquals("albam-mate:production:session", productionSession.redisNamespace());
		assertEquals(Duration.ofMinutes(30).toSeconds(), localMultiSession.maxInactiveIntervalInSeconds());
		assertEquals(Duration.ofMinutes(30).toSeconds(), productionSession.maxInactiveIntervalInSeconds());
		assertTrue(AnnotatedElementUtils.hasAnnotation(
			SessionConfiguration.InMemorySessionRepositoryConfiguration.class, EnableSpringHttpSession.class));
		assertFalse(AnnotatedElementUtils.hasAnnotation(
			nestedRedisConfiguration("LocalSessionRepositoryConfiguration"), EnableSpringHttpSession.class));
		assertFalse(AnnotatedElementUtils.hasAnnotation(
			nestedRedisConfiguration("ProductionSessionRepositoryConfiguration"), EnableSpringHttpSession.class));
	}

	@Test
	void T1_local_Compose는_프록시_Spring_2대_PostgreSQL_Redis를_선언한다() throws Exception {
		String compose = Files.readString(Path.of("compose.local.yml"));
		String proxy = Files.readString(Path.of("frontend/nginx.local.conf"));

		assertTrue(compose.contains("  redis:"));
		assertTrue(compose.contains("  spring-1:"));
		assertTrue(compose.contains("  spring-2:"));
		assertTrue(compose.contains("  proxy:"));
		assertTrue(compose.contains("127.0.0.1:"));
		assertEquals(2, compose.lines().filter(line -> line.trim().equals("- spring")).count());
		assertTrue(proxy.contains("resolver 127.0.0.11"));
		assertTrue(proxy.contains("set $spring_upstream http://spring:8080"));
	}

	@Test
	void T6_local_시드_콜백은_유지하고_legacy_local_multi_프로필은_제거한다() throws Exception {
		String localProfile = Files.readString(Path.of("src/main/resources/application-local.yml"));

		assertTrue(localProfile.contains("classpath:db/local"));
		assertFalse(Files.exists(Path.of("src/main/resources/application-local-multi.yml")));
	}

	@Test
	void T7_CI와_로컬_계약은_local_multi_실행_경로를_참조하지_않는다() throws Exception {
		String ci = Files.readString(Path.of(".github/workflows/ci.yml"));

		assertFalse(ci.contains("local-multi"));
		assertFalse(Files.exists(Path.of("compose.local-multi.yml")));
		assertFalse(Files.exists(Path.of("frontend/nginx.local-multi.conf")));
	}

	@Test
	void T2_local과_production_연결_factory는_1초_연결과_2초_명령_timeout을_공유한다() {
		assertRedisTimeouts("local");
		assertRedisTimeouts("production");
	}

	@Test
	void Redis_연결은_프로필_외부_설정값을_사용한다() {
		try (AnnotationConfigApplicationContext context = redisSessionContext("local")) {
			LettuceConnectionFactory connectionFactory = context.getBean(LettuceConnectionFactory.class);

			assertEquals("redis", connectionFactory.getHostName());
			assertEquals(6379, connectionFactory.getPort());
		}
	}

	@Test
	void production은_Redis_세션_연결을_등록한다() {
		try (AnnotationConfigApplicationContext context = redisSessionContext("production")) {
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
		try (AnnotationConfigApplicationContext context = redisSessionContext("local")) {
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

	private AnnotationConfigApplicationContext redisSessionContext(String profile) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().setActiveProfiles(profile);
		TestPropertyValues.of("app.redis.host=redis", "app.redis.port=6379").applyTo(context);
		context.register(RedisSessionConfiguration.class);
		context.refresh();
		return context;
	}

	private void assertRedisTimeouts(String profile) {
		try (AnnotationConfigApplicationContext context = redisSessionContext(profile)) {
			LettuceConnectionFactory connectionFactory = context.getBean(LettuceConnectionFactory.class);

			assertEquals(Duration.ofSeconds(2), connectionFactory.getClientConfiguration().getCommandTimeout());
			assertEquals(Duration.ofSeconds(1), connectionFactory.getClientConfiguration()
				.getClientOptions().orElseThrow().getSocketOptions().getConnectTimeout());
			assertFalse(connectionFactory.getClientConfiguration().getClientOptions().orElseThrow().isAutoReconnect());
			assertEquals(
				io.lettuce.core.ClientOptions.DisconnectedBehavior.REJECT_COMMANDS,
				connectionFactory.getClientConfiguration().getClientOptions().orElseThrow().getDisconnectedBehavior());
		}
	}

	private Class<?> nestedRedisConfiguration(String simpleName) {
		return Arrays.stream(RedisSessionConfiguration.class.getDeclaredClasses())
			.filter(candidate -> candidate.getSimpleName().equals(simpleName))
			.findFirst()
			.orElseThrow();
	}
}
