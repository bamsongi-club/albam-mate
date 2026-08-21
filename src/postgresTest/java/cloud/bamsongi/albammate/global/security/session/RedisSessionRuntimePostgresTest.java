package cloud.bamsongi.albammate.global.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;

@Testcontainers
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"app.security.cookie.secure=false",
	"app.notification.relay.enabled=false"
})
class RedisSessionRuntimePostgresTest {

	private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE = cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages
		.postgres18();
	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final String SESSION_KEY_PREFIX = "albam-mate:local:session:sessions:";
	private static final long SESSION_TTL_SECONDS = 30 * 60;
	private static final long SESSION_TTL_TOLERANCE_SECONDS = 60;
	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_session_redis_test");

	@Container
	static final GenericContainer REDIS = new GenericContainer(REDIS_IMAGE)
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	@LocalServerPort
	private int firstPort;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	@Qualifier("redisSessionConnectionFactory") private RedisConnectionFactory redisConnectionFactory;

	@Autowired
	private UserAccountService userAccountService;

	@DynamicPropertySource
	static void localMultiProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("ALBAM_MATE_LOCAL_REDIS_HOST", REDIS::getHost);
		registry.add("ALBAM_MATE_LOCAL_REDIS_PORT", () -> REDIS.getMappedPort(6379));
	}

	@Test
	void 두_애플리케이션이_공유_Redis_세션을_사용하고_Redis_장애시_인메모리로_fallback하지_않는다() throws Exception {
		String email = "redis-session-runtime@example.com";
		String password = "123456789012345";
		var account = userAccountService.createAccount(command(email, password, "Redis 세션 사용자"));

		try (ConfigurableApplicationContext secondContext = secondApplicationContext()) {
			assertRedisSessionConnectionPolicy(applicationContext);
			assertRedisSessionConnectionPolicy(secondContext);
			HttpClient client = HttpClient.newBuilder()
				.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
				.build();
			URI firstUri = URI.create("http://localhost:" + firstPort);
			URI secondUri = URI.create("http://localhost:" + serverPort(secondContext));

			HttpResponse<String> csrf = get(client, firstUri.resolve("/api/auth/csrf"));
			assertEquals(200, csrf.statusCode());
			String csrfToken = csrfToken(csrf.body());
			HttpResponse<String> login = post(
				client,
				firstUri.resolve("/api/auth/login"),
				"{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
				csrfToken);
			assertEquals(200, login.statusCode());
			assertEquals(account.id(), accountId(login.body()));

			HttpCookie sessionCookie = cookieNamed(client, "JSESSIONID");
			assertNotNull(sessionCookie);
			assertEquals(200, get(client, firstUri.resolve("/api/users/me")).statusCode());
			HttpResponse<String> secondInstanceProfile = getWithSession(
				secondUri.resolve("/api/users/me"), sessionCookie);
			assertEquals(200, secondInstanceProfile.statusCode());

			assertTrue(applicationContext.getBeansOfType(
				org.springframework.session.MapSessionRepository.class).isEmpty());
			assertTrue(secondContext.getBeansOfType(
				org.springframework.session.MapSessionRepository.class).isEmpty());
			StringRedisTemplate redis = new StringRedisTemplate(redisConnectionFactory);
			redis.afterPropertiesSet();
			String sessionKey = awaitSessionKey(redis, sessionCookie.getValue());
			assertEquals(SESSION_KEY_PREFIX + sessionCookie.getValue(), sessionKey);
			Long ttl = redis.getExpire(sessionKey, TimeUnit.SECONDS);
			assertNotNull(ttl);
			assertTrue(
				ttl >= SESSION_TTL_SECONDS - SESSION_TTL_TOLERANCE_SECONDS && ttl <= SESSION_TTL_SECONDS,
				"actual TTL=" + ttl);

			assertTrue(redis.expire(sessionKey, 1, TimeUnit.SECONDS));
			awaitSessionExpiry(redis, sessionKey);
			HttpResponse<String> expiredSession = getWithSession(
				firstUri.resolve("/api/users/me"), sessionCookie);
			assertEquals(401, expiredSession.statusCode());

			CookieManager activeSessionCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
			HttpClient activeSessionClient = HttpClient.newBuilder()
				.cookieHandler(activeSessionCookies)
				.build();
			HttpResponse<String> activeSessionCsrf = get(activeSessionClient, firstUri.resolve("/api/auth/csrf"));
			assertEquals(200, activeSessionCsrf.statusCode());
			HttpResponse<String> activeSessionLogin = post(
				activeSessionClient,
				firstUri.resolve("/api/auth/login"),
				"{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
				csrfToken(activeSessionCsrf.body()));
			assertEquals(200, activeSessionLogin.statusCode());
			HttpCookie activeSessionCookie = cookieNamed(activeSessionClient, "JSESSIONID");
			String activeSessionKey = awaitSessionKey(redis, activeSessionCookie.getValue());
			assertTrue(redis.hasKey(activeSessionKey));

			REDIS.stop();
			HttpResponse<String> unavailable = getWithSession(secondUri.resolve("/api/users/me"), activeSessionCookie);
			assertEquals(503, unavailable.statusCode());
			assertTrue(unavailable.body().contains("SERVICE_UNAVAILABLE"), unavailable.body());
			assertTrue(unavailable.headers().firstValue("Retry-After").isEmpty());
		}
	}

	private void assertRedisSessionConnectionPolicy(ApplicationContext context) {
		LettuceConnectionFactory connectionFactory = context.getBean(
			"redisSessionConnectionFactory", LettuceConnectionFactory.class);

		assertTrue(connectionFactory.getShareNativeConnection());
		assertTrue(connectionFactory.getClientConfiguration().getClientOptions().orElseThrow().isAutoReconnect());
	}

	private ConfigurableApplicationContext secondApplicationContext() {
		return new SpringApplicationBuilder(AlbamMateApplication.class).run(
			"--spring.profiles.active=local",
			"--server.port=0",
			"--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
			"--spring.datasource.username=" + POSTGRES.getUsername(),
			"--spring.datasource.password=" + POSTGRES.getPassword(),
			"--spring.flyway.enabled=false",
			"--app.redis.host=" + REDIS.getHost(),
			"--app.redis.port=" + REDIS.getMappedPort(6379),
			"--app.security.cookie.secure=false",
			"--app.notification.relay.enabled=false");
	}

	private int serverPort(ConfigurableApplicationContext context) {
		return ((org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext)context)
			.getWebServer()
			.getPort();
	}

	private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> getWithSession(URI uri, HttpCookie sessionCookie) throws Exception {
		return HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(uri)
				.header("Cookie", "JSESSIONID=" + sessionCookie.getValue())
				.GET()
				.build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> post(HttpClient client, URI uri, String body, String csrfToken) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri)
				.header("Content-Type", "application/json")
				.header("X-XSRF-TOKEN", csrfToken)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpCookie cookieNamed(HttpClient client, String name) {
		CookieManager cookieManager = (CookieManager)client.cookieHandler().orElseThrow();
		return cookieManager.getCookieStore().getCookies().stream()
			.filter(cookie -> cookie.getName().equals(name))
			.findFirst()
			.orElseThrow();
	}

	private String csrfToken(String body) {
		Matcher matcher = CSRF_TOKEN_PATTERN.matcher(body);
		assertTrue(matcher.find(), body);
		return matcher.group(1);
	}

	private long accountId(String body) {
		Matcher matcher = Pattern.compile("\\\"id\\\":(\\d+)").matcher(body);
		assertTrue(matcher.find(), body);
		return Long.parseLong(matcher.group(1));
	}

	private String awaitSessionKey(StringRedisTemplate redis, String sessionId) throws InterruptedException {
		String sessionKey = SESSION_KEY_PREFIX + sessionId;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (Boolean.TRUE.equals(redis.hasKey(sessionKey))) {
				return sessionKey;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Redis session key was not created: " + sessionKey);
	}

	private void awaitSessionExpiry(StringRedisTemplate redis, String sessionKey) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (!Boolean.TRUE.equals(redis.hasKey(sessionKey))) {
				return;
			}
			Thread.sleep(100);
		}
		assertFalse(redis.hasKey(sessionKey), "Redis session key did not expire: " + sessionKey);
	}

	private CreateUserAccountCommand command(String email, String password, String nickname) {
		return new CreateUserAccountCommand(
			UserEmail.from(email).orElseThrow(),
			RawPassword.from(password).orElseThrow(),
			UserNickname.from(nickname).orElseThrow());
	}
}
