package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.context.ActiveProfiles;

/**
 * T9: local-multi에서 세션 저장소를 확인할 수 없으면 채팅 세 엔드포인트가 {@code Retry-After} 없는 503으로 실패하고
 * WebSocket handshake도 upgrade 전에 거절되며, 인메모리 저장소로 자동 fallback하지 않는지 실제 HTTP로 검증한다.
 */
@ActiveProfiles("local-multi")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:chat-session-store-unavailable;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.locations=classpath:db/migration",
	"app.redis.host=127.0.0.1",
	"app.redis.port=1",
	"app.security.cookie.secure=false",
	"app.notification.relay.enabled=false"
})
class ChatSessionStoreUnavailableIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void 세션_저장소를_확인할_수_없으면_메시지_전송이_Retry_After_없는_503으로_실패한다() throws Exception {
		HttpResponse<String> response = post(
			"/api/rooms/1/chat/messages", "{\"clientMessageId\":\"c-1\",\"content\":\"본문\"}");

		assertEquals(503, response.statusCode());
		assertTrue(response.body().contains("SERVICE_UNAVAILABLE"), response.body());
		assertTrue(response.headers().firstValue("Retry-After").isEmpty());
	}

	@Test
	void 세션_저장소를_확인할_수_없으면_이력_조회도_503으로_실패한다() throws Exception {
		HttpResponse<String> response = get("/api/rooms/1/chat/messages");

		assertEquals(503, response.statusCode());
		assertTrue(response.body().contains("SERVICE_UNAVAILABLE"), response.body());
	}

	@Test
	void 세션_저장소를_확인할_수_없으면_WebSocket_handshake도_upgrade_전에_503으로_거절된다() {
		ExecutionException exception = assertThrows(ExecutionException.class, this::connectWebSocket);

		WebSocketHandshakeException handshakeException = (WebSocketHandshakeException)exception.getCause();
		assertEquals(503, handshakeException.getResponse().statusCode());
	}

	@Test
	void 세션_저장소_실패는_인메모리_저장소로_자동_fallback하지_않는다() {
		assertTrue(applicationContext.getBeansOfType(MapSessionRepository.class).isEmpty());
	}

	private void connectWebSocket() throws Exception {
		HttpClient.newHttpClient()
			.newWebSocketBuilder()
			.header("Origin", "http://localhost:5173")
			.buildAsync(URI.create("ws://localhost:" + port + "/api/rooms/1/chat/ws"), new WebSocket.Listener() {})
			.get(10, TimeUnit.SECONDS);
	}

	private HttpResponse<String> get(String path) throws Exception {
		return HttpClient.newHttpClient()
			.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> post(String path, String body) throws Exception {
		return HttpClient.newHttpClient()
			.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
					.build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}
}
