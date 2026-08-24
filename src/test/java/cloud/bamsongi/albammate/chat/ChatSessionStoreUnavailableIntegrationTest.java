package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T9: local에서 세션 저장소를 확인할 수 없으면 채팅 세 엔드포인트가 {@code Retry-After} 없는 503으로 실패하고
 * WebSocket handshake도 upgrade 전에 거절되며, 인메모리 저장소로 자동 fallback하지 않는지 실제 HTTP로 검증한다.
 */
@ActiveProfiles("local")
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

	private static final String UNAVAILABLE_SESSION_COOKIE = "JSESSIONID=redis-session-unavailable";

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationContext applicationContext;
	@Autowired
	private ObjectMapper objectMapper;
	@MockitoBean(name = "chatRealtimeMessageListenerContainer")
	private RedisMessageListenerContainer chatRealtimeMessageListenerContainer;

	@Test
	void 세션_저장소를_확인할_수_없으면_메시지_전송이_Retry_After_없는_503으로_실패한다() throws Exception {
		assertRedisSessionConnectionPolicy();
		HttpResponse<String> response = post(
			"/api/rooms/1/chat/messages", "{\"clientMessageId\":\"c-1\",\"content\":\"본문\"}");

		assertEquals(503, response.statusCode());
		assertServiceUnavailableResponse(response.body());
		assertTrue(response.headers().firstValue("Retry-After").isEmpty());
	}

	@Test
	void 세션_저장소를_확인할_수_없으면_이력_조회도_503으로_실패한다() throws Exception {
		assertRedisSessionConnectionPolicy();
		HttpResponse<String> response = get("/api/rooms/1/chat/messages");

		assertEquals(503, response.statusCode());
		assertServiceUnavailableResponse(response.body());
		assertTrue(response.headers().firstValue("Retry-After").isEmpty());
	}

	@Test
	void 세션_저장소를_확인할_수_없으면_WebSocket_handshake도_upgrade_전에_503_공통_봉투로_거절된다() throws Exception {
		assertRedisSessionConnectionPolicy();
		UpgradeResponse response = upgradeWebSocket();

		assertEquals(503, response.statusCode());
		assertEquals("application/json", response.contentType().split(";")[0]);
		assertServiceUnavailableResponse(response.body());
		assertTrue(response.retryAfter() == null);
	}

	@Test
	void 세션_저장소_실패는_인메모리_저장소로_자동_fallback하지_않는다() {
		assertRedisSessionConnectionPolicy();
		assertTrue(applicationContext.getBeansOfType(MapSessionRepository.class).isEmpty());
	}

	private void assertRedisSessionConnectionPolicy() {
		LettuceConnectionFactory connectionFactory = applicationContext.getBean(
			"redisSessionConnectionFactory", LettuceConnectionFactory.class);

		assertTrue(connectionFactory.getShareNativeConnection());
		assertTrue(connectionFactory.getClientConfiguration().getClientOptions().orElseThrow().isAutoReconnect());
	}

	private void assertServiceUnavailableResponse(String body) throws Exception {
		JsonNode response = objectMapper.readTree(body);

		assertEquals(503, response.path("status").asInt());
		assertEquals(ErrorCode.SERVICE_UNAVAILABLE.getCode(), response.path("code").asString());
		assertEquals(ErrorCode.SERVICE_UNAVAILABLE.getMessage(), response.path("message").asString());
		assertTrue(response.path("data").isNull());
	}

	private UpgradeResponse upgradeWebSocket() throws Exception {
		try (Socket socket = new Socket("localhost", port)) {
			socket.setSoTimeout(5000);
			String request = "GET /api/rooms/1/chat/ws HTTP/1.1\r\n"
				+ "Host: localhost:" + port + "\r\n"
				+ "Connection: Upgrade\r\n"
				+ "Upgrade: websocket\r\n"
				+ "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
				+ "Sec-WebSocket-Version: 13\r\n"
				+ "Origin: http://localhost:5173\r\n"
				+ "Cookie: " + UNAVAILABLE_SESSION_COOKIE + "\r\n\r\n";
			socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().flush();

			InputStream input = socket.getInputStream();
			String headers = readHeaders(input);
			int contentLength = Integer.parseInt(header(headers, "content-length"));
			String body = new String(input.readNBytes(contentLength), StandardCharsets.UTF_8);
			return new UpgradeResponse(statusCode(headers), header(headers, "content-type"),
				header(headers, "retry-after"), body);
		}
	}

	private String readHeaders(InputStream input) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		int previous = -1;
		int current;
		while ((current = input.read()) != -1) {
			bytes.write(current);
			if (previous == '\r' && current == '\n' && endsWithHeaderSeparator(bytes)) {
				return bytes.toString(StandardCharsets.US_ASCII);
			}
			previous = current;
		}
		throw new AssertionError("WebSocket handshake response headers were not completed");
	}

	private boolean endsWithHeaderSeparator(ByteArrayOutputStream bytes) {
		byte[] value = bytes.toByteArray();
		int length = value.length;
		return length >= 4 && value[length - 4] == '\r' && value[length - 3] == '\n'
			&& value[length - 2] == '\r' && value[length - 1] == '\n';
	}

	private int statusCode(String headers) {
		return Integer.parseInt(headers.substring(0, headers.indexOf('\r')).split(" ")[1]);
	}

	private String header(String headers, String name) {
		return headers.lines()
			.map(line -> line.split(": ", 2))
			.filter(parts -> parts.length == 2 && parts[0].equalsIgnoreCase(name))
			.map(parts -> parts[1])
			.findFirst()
			.orElse(null);
	}

	private record UpgradeResponse(int statusCode, String contentType, String retryAfter, String body) {
	}

	private HttpResponse<String> get(String path) throws Exception {
		return HttpClient.newHttpClient()
			.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
					.header("Cookie", UNAVAILABLE_SESSION_COOKIE)
					.GET()
					.build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> post(String path, String body) throws Exception {
		return HttpClient.newHttpClient()
			.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
					.header("Content-Type", "application/json")
					.header("Cookie", UNAVAILABLE_SESSION_COOKIE)
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
					.build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}
}
