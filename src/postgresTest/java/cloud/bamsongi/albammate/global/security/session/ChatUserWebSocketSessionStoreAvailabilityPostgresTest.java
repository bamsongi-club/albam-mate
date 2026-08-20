package cloud.bamsongi.albammate.global.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T5: 실제 PostgreSQL·Redis Testcontainers 환경에서 CHAT-08 사용자 WebSocket handshake 경로가
 * {@link ChatSessionStoreAvailabilityFilter}의 세션 저장소 가용성 gate를 실제로 통과·거절하는지 확인한다.
 */
@Testcontainers
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"app.security.cookie.secure=false",
	"app.notification.relay.enabled=false"
})
class ChatUserWebSocketSessionStoreAvailabilityPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String REDIS_IMAGE = "redis:8.4-alpine";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_ws_session_test");

	@Container
	static final GenericContainer REDIS = new GenericContainer(REDIS_IMAGE)
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void localMultiProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("ALBAM_MATE_LOCAL_REDIS_HOST", REDIS::getHost);
		registry.add("ALBAM_MATE_LOCAL_REDIS_PORT", () -> REDIS.getMappedPort(6379));
	}

	@Test
	void Redis가_정상이면_필터를_통과하고_중단되면_사용자_WebSocket_handshake_경로가_Retry_After_없는_503을_반환한다()
		throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		URI uri = URI.create("http://localhost:" + port + "/api/users/me/chat/ws");

		HttpResponse<String> passedThroughFilter = get(client, uri);
		assertEquals(401, passedThroughFilter.statusCode());
		assertTrue(passedThroughFilter.body().contains("UNAUTHENTICATED"), passedThroughFilter.body());

		REDIS.stop();

		HttpResponse<String> unavailable = get(client, uri);
		assertEquals(503, unavailable.statusCode());
		assertTrue(unavailable.body().contains("SERVICE_UNAVAILABLE"), unavailable.body());
		assertTrue(unavailable.headers().firstValue("Retry-After").isEmpty());
	}

	private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}
}
