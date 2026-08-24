package cloud.bamsongi.albammate.global.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.MapSessionRepository;
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

/** T7: production profile도 두 인스턴스가 Redis Spring Session으로 같은 JSESSIONID를 확인하는지 검증한다. */
@Testcontainers
@ActiveProfiles("production")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"app.security.cookie.secure=false",
	"management.otlp.metrics.export.enabled=false",
	"app.notification.relay.enabled=false"
})
class RedisSessionProductionProfilePostgresTest {

	private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE = cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages
		.postgres18();
	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final String PASSWORD = "123456789012345";
	private static final String SESSION_KEY_PREFIX = "albam-mate:production:session:sessions:";
	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_production_session_test");

	@Container
	static final GenericContainer REDIS = new GenericContainer(REDIS_IMAGE)
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	@LocalServerPort
	private int firstPort;

	@Autowired
	private ApplicationContext applicationContext;
	@Autowired
	private RedisConnectionFactory redisConnectionFactory;
	@Autowired
	private UserAccountService userAccountService;

	@DynamicPropertySource
	static void productionProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("app.redis.host", REDIS::getHost);
		registry.add("app.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("app.monitoring.upstream-role", () -> "app1");
	}

	@Test
	void production_두_인스턴스가_공유_Redis_세션으로_같은_JSESSIONID를_확인한다() throws Exception {
		String email = "production-session-" + UUID.randomUUID() + "@example.com";
		userAccountService.createAccount(command(email, "운영 세션 사용자"));

		try (ConfigurableApplicationContext secondContext = secondApplicationContext()) {
			HttpClient client = HttpClient.newBuilder()
				.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
				.build();
			URI firstUri = URI.create("http://localhost:" + firstPort);
			URI secondUri = URI.create("http://localhost:" + serverPort(secondContext));

			String csrfToken = csrfToken(get(client, firstUri.resolve("/api/auth/csrf")).body());
			assertEquals(
				200,
				post(client, firstUri.resolve("/api/auth/login"), loginBody(email), csrfToken).statusCode());
			HttpCookie sessionCookie = cookieNamed(client, "JSESSIONID");

			assertEquals(200, get(client, firstUri.resolve("/api/users/me")).statusCode());
			assertEquals(200, getWithSession(secondUri.resolve("/api/users/me"), sessionCookie).statusCode());
			assertTrue(applicationContext.getBeansOfType(MapSessionRepository.class).isEmpty());
			assertTrue(secondContext.getBeansOfType(MapSessionRepository.class).isEmpty());

			StringRedisTemplate redis = new StringRedisTemplate(redisConnectionFactory);
			redis.afterPropertiesSet();
			assertEquals(
				SESSION_KEY_PREFIX + sessionCookie.getValue(),
				awaitSessionKey(redis, sessionCookie.getValue()));
		}
	}

	private ConfigurableApplicationContext secondApplicationContext() {
		return new SpringApplicationBuilder(AlbamMateApplication.class).run(
			"--spring.profiles.active=production",
			"--server.port=0",
			"--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
			"--spring.datasource.username=" + POSTGRES.getUsername(),
			"--spring.datasource.password=" + POSTGRES.getPassword(),
			"--spring.flyway.enabled=false",
			"--spring.data.redis.host=" + REDIS.getHost(),
			"--spring.data.redis.port=" + REDIS.getMappedPort(6379),
			"--app.redis.host=" + REDIS.getHost(),
			"--app.redis.port=" + REDIS.getMappedPort(6379),
			"--app.monitoring.upstream-role=app2",
			"--app.security.cookie.secure=false",
			"--management.otlp.metrics.export.enabled=false",
			"--app.notification.relay.enabled=false");
	}

	private int serverPort(ConfigurableApplicationContext context) {
		return ((ServletWebServerApplicationContext)context).getWebServer().getPort();
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
			.filter(cookie -> name.equals(cookie.getName()))
			.findFirst()
			.orElseThrow();
	}

	private String csrfToken(String body) {
		Matcher matcher = CSRF_TOKEN_PATTERN.matcher(body);
		assertTrue(matcher.find(), body);
		return matcher.group(1);
	}

	private String loginBody(String email) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
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

	private CreateUserAccountCommand command(String email, String nickname) {
		return new CreateUserAccountCommand(
			UserEmail.from(email).orElseThrow(), RawPassword.from(PASSWORD).orElseThrow(),
			UserNickname.from(nickname).orElseThrow());
	}
}
