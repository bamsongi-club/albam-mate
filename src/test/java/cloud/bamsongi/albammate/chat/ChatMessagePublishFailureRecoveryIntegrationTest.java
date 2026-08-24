package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.ChatRealtimeSignalGateway;
import cloud.bamsongi.albammate.chat.dto.ChatMessagePageResponse;
import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.chat.service.ChatMessageCommandService;
import cloud.bamsongi.albammate.chat.service.ChatMessageHistoryQueryService;
import cloud.bamsongi.albammate.chat.service.ChatMessageSendResult;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * T8: 커밋 뒤 Pub/Sub 발행이 실패해도 저장 성공 응답과 이력이 유지되고, 이력 조회와 재연결이 누락분을 복구하는지 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"app.security.cookie.secure=false",
	"app.notification.relay.enabled=false"
})
@Import(ChatMessagePublishFailureRecoveryIntegrationTest.TestBeans.class)
class ChatMessagePublishFailureRecoveryIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
	private static final String PASSWORD = "123456789012345";
	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");
	private static final Pattern EVENT_ID_PATTERN = Pattern.compile("\\\"eventId\\\":(\\d+)");

	@Autowired
	private ChatMessageCommandService chatMessageCommandService;
	@Autowired
	private ChatMessageHistoryQueryService chatMessageHistoryQueryService;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private UserAccountService userAccountService;
	@Autowired
	private ChatRealtimePublishControl chatRealtimePublishControl;
	@Autowired
	private MeterRegistry meterRegistry;
	@Autowired
	@Qualifier("chatRealtimeSignalExecutor") private ExecutorService chatRealtimeSignalExecutor;
	@LocalServerPort
	private int serverPort;

	private Long userId;
	private Long roomId;
	private Long chatRoomId;

	@AfterEach
	void tearDown() {
		chatRealtimePublishControl.reset();
		if (chatRoomId != null) {
			jdbcTemplate.update("delete from chat_messages where chat_room_id = ?", chatRoomId);
			jdbcTemplate.update("delete from chat_rooms where id = ?", chatRoomId);
		}
		if (roomId != null) {
			jdbcTemplate.update("delete from rooms where id = ?", roomId);
		}
		if (userId != null) {
			jdbcTemplate.update("delete from users where id = ?", userId);
		}
	}

	@Test
	void T1_HTTP_두줄_메시지는_저장_이력_실시간_수신에_LF로_보존된다() throws Exception {
		String email = "chat-line-break-" + UUID.randomUUID() + "@example.com";
		long currentUserId = userAccountService.createAccount(command(email, "줄바꿈검증")).id();
		userId = currentUserId;
		Room room = createChatRoom(currentUserId);

		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
		URI serverUri = URI.create("http://localhost:" + serverPort);
		String csrfToken = csrfToken(get(client, serverUri.resolve("/api/auth/csrf")).body());
		assertEquals(
			200,
			post(client, serverUri.resolve("/api/auth/login"), loginBody(email), csrfToken).statusCode());
		csrfToken = csrfToken(get(client, serverUri.resolve("/api/auth/csrf")).body());

		LinkedBlockingQueue<String> liveReceivedFrames = new LinkedBlockingQueue<>();
		WebSocket liveWebSocket = connect(serverUri, room.getId(), null, cookieManager, liveReceivedFrames);
		try {
			awaitActiveWebSocketConnection();
			HttpResponse<String> response = post(
				client,
				serverUri.resolve("/api/rooms/" + room.getId() + "/chat/messages"),
				"{\"clientMessageId\":\"line-break-http\",\"content\":\"첫 줄\\n둘째 줄\"}",
				csrfToken);

			assertEquals(201, response.statusCode(), response.body());
			assertEquals("첫 줄\n둘째 줄", chatMessageRepository.findAll().get(0).getContent());
			ChatMessagePageResponse history = chatMessageHistoryQueryService.history(
				currentUserId, room.getId(), null, 50);
			assertEquals(List.of("첫 줄\n둘째 줄"),
				history.messages().stream().map(message -> message.content()).toList());
			String liveFrame = liveReceivedFrames.poll(10, TimeUnit.SECONDS);
			assertTrue(liveFrame != null, "활성 WebSocket 연결이 두 줄 메시지 프레임을 받지 못했습니다.");
			assertTrue(liveFrame.contains("\"content\":\"첫 줄\\n둘째 줄\""), liveFrame);
		} finally {
			liveWebSocket.abort();
		}
	}

	@Test
	void T3_LF_CRLF_외_제어문자는_HTTP_400이고_저장과_커밋_신호를_만들지_않는다() throws Exception {
		String email = "chat-control-" + UUID.randomUUID() + "@example.com";
		long currentUserId = userAccountService.createAccount(command(email, "제어문자검증")).id();
		userId = currentUserId;
		Room room = createChatRoom(currentUserId);

		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
		URI serverUri = URI.create("http://localhost:" + serverPort);
		String csrfToken = csrfToken(get(client, serverUri.resolve("/api/auth/csrf")).body());
		assertEquals(
			200,
			post(client, serverUri.resolve("/api/auth/login"), loginBody(email), csrfToken).statusCode());
		csrfToken = csrfToken(get(client, serverUri.resolve("/api/auth/csrf")).body());

		assertHttpValidationError(post(
			client,
			serverUri.resolve("/api/rooms/" + room.getId() + "/chat/messages"),
			"{\"clientMessageId\":\"lone-cr\",\"content\":\"첫 줄\\r둘째 줄\"}", csrfToken));
		assertHttpValidationError(post(
			client,
			serverUri.resolve("/api/rooms/" + room.getId() + "/chat/messages"),
			"{\"clientMessageId\":\"tab\",\"content\":\"첫 줄\\t둘째 줄\"}", csrfToken));
		assertHttpValidationError(post(
			client,
			serverUri.resolve("/api/rooms/" + room.getId() + "/chat/messages"),
			"{\"clientMessageId\":\"nul\",\"content\":\"첫 줄\\u0000둘째 줄\"}", csrfToken));

		assertEquals(0, chatMessageRepository.count());
		assertEquals(0, chatRealtimePublishControl.publishAttempts());
	}

	@Test
	void T8_발행_실패_뒤_afterMessageId_WebSocket_재연결은_누락분을_ASC_한번만_수신한다() throws Exception {
		chatRealtimePublishControl.reset();
		String email = "chat-publish-failure-" + UUID.randomUUID() + "@example.com";
		long currentUserId = userAccountService.createAccount(command(email, "발행실패검증")).id();
		userId = currentUserId;
		Room room = createChatRoom(currentUserId);

		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
		URI serverUri = URI.create("http://localhost:" + serverPort);
		String csrfToken = csrfToken(get(client, serverUri.resolve("/api/auth/csrf")).body());
		assertEquals(
			200,
			post(client, serverUri.resolve("/api/auth/login"), loginBody(email), csrfToken).statusCode());

		LinkedBlockingQueue<String> liveReceivedFrames = new LinkedBlockingQueue<>();
		WebSocket liveWebSocket = connect(serverUri, room.getId(), null, cookieManager, liveReceivedFrames);
		try {
			awaitActiveWebSocketConnection();
			ChatMessageSendResult result = chatMessageCommandService.send(
				currentUserId, room.getId(), new ChatMessageSendRequest("publish-success-1", "첫 번째 정상 발행"));
			assertTrue(result.created());
			String firstLiveFrame = liveReceivedFrames.poll(10, TimeUnit.SECONDS);
			assertTrue(firstLiveFrame != null, "활성 WebSocket 연결이 첫 메시지 프레임을 받지 못했습니다.");
			assertEquals(result.message().messageId(), eventId(firstLiveFrame));
			assertEquals(1, chatRealtimePublishControl.publishAttempts());
			assertEquals(0, chatRealtimePublishControl.failedPublishAttempts());
			awaitRealtimeSignalDelivery();
			assertNull(liveReceivedFrames.poll(1, TimeUnit.SECONDS), "첫 메시지가 중복 전달됐습니다.");

			chatRealtimePublishControl.failPublishes();
			ChatMessageSendResult missedResult = chatMessageCommandService.send(
				currentUserId, room.getId(), new ChatMessageSendRequest("publish-fail-2", "두 번째 발행 실패"));
			ChatMessageSendResult laterMissedResult = chatMessageCommandService.send(
				currentUserId, room.getId(), new ChatMessageSendRequest("publish-fail-3", "세 번째 발행 실패"));
			assertTrue(missedResult.created());
			assertTrue(laterMissedResult.created());
			assertEquals(3, chatRealtimePublishControl.publishAttempts());
			assertEquals(2, chatRealtimePublishControl.failedPublishAttempts());
			assertNull(
				liveReceivedFrames.poll(1, TimeUnit.SECONDS),
				"Pub/Sub 발행 실패 뒤 활성 WebSocket 연결이 메시지를 받았습니다.");
			assertEquals(3, chatMessageRepository.count());

			ChatMessagePageResponse history = chatMessageHistoryQueryService.history(
				currentUserId, room.getId(), null, 50);
			assertEquals(3, history.messages().size());
			assertTrue(history.messages().stream()
				.anyMatch(message -> message.messageId() == result.message().messageId()));
			assertTrue(history.messages().stream()
				.anyMatch(message -> message.messageId() == missedResult.message().messageId()));
			assertTrue(history.messages().stream()
				.anyMatch(message -> message.messageId() == laterMissedResult.message().messageId()));

			liveWebSocket.abort();
			LinkedBlockingQueue<String> recoveredFrames = new LinkedBlockingQueue<>();
			WebSocket recoveredWebSocket = connect(
				serverUri, room.getId(), result.message().messageId(), cookieManager, recoveredFrames);
			try {
				String firstRecoveredFrame = recoveredFrames.poll(10, TimeUnit.SECONDS);
				String secondRecoveredFrame = recoveredFrames.poll(10, TimeUnit.SECONDS);
				assertTrue(firstRecoveredFrame != null, "afterMessageId 재연결이 첫 누락 메시지 프레임을 받지 못했습니다.");
				assertTrue(secondRecoveredFrame != null, "afterMessageId 재연결이 두 번째 누락 메시지 프레임을 받지 못했습니다.");
				assertTrue(firstRecoveredFrame.contains("\"type\":\"MESSAGE_CREATED\""), firstRecoveredFrame);
				assertTrue(secondRecoveredFrame.contains("\"type\":\"MESSAGE_CREATED\""), secondRecoveredFrame);
				assertEquals(
					List.of(missedResult.message().messageId(), laterMissedResult.message().messageId()),
					List.of(eventId(firstRecoveredFrame), eventId(secondRecoveredFrame)));
				assertNull(recoveredFrames.poll(1, TimeUnit.SECONDS), "동일 messageId가 중복 전달됐습니다.");
			} finally {
				recoveredWebSocket.abort();
			}
		} finally {
			liveWebSocket.abort();
		}
	}

	private void awaitActiveWebSocketConnection() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			Gauge activeConnections = meterRegistry.find("chat.websocket.connections.active").gauge();
			if (activeConnections != null && activeConnections.value() >= 1.0) {
				return;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("WebSocket 연결 등록이 제한 시간 안에 완료되지 않았습니다.");
	}

	private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

	private WebSocket connect(
		URI serverUri, long roomId, Long afterMessageId, CookieManager cookieManager,
		LinkedBlockingQueue<String> receivedFrames) throws Exception {
		String sessionId = sessionId(cookieManager);
		return HttpClient.newHttpClient()
			.newWebSocketBuilder()
			.header("Cookie", "JSESSIONID=" + sessionId)
			.header("Origin", "http://localhost:5173")
			.buildAsync(
				URI.create("ws://localhost:" + serverUri.getPort() + "/api/rooms/" + roomId
					+ "/chat/ws" + (afterMessageId == null ? "" : "?afterMessageId=" + afterMessageId)),
				new WebSocket.Listener() {

					@Override
					public void onOpen(WebSocket webSocket) {
						webSocket.request(1);
					}

					@Override
					public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
						receivedFrames.add(data.toString());
						webSocket.request(1);
						return null;
					}
				})
			.get(10, TimeUnit.SECONDS);
	}

	private String sessionId(CookieManager cookieManager) {
		return cookieManager.getCookieStore().getCookies().stream()
			.filter(cookie -> "JSESSIONID".equals(cookie.getName()))
			.findFirst()
			.orElseThrow()
			.getValue();
	}

	/** 첫 발행 signal의 비동기 전달과 연결 cursor 갱신이 끝난 뒤 발행 실패 상태로 전환한다. */
	private void awaitRealtimeSignalDelivery() throws Exception {
		chatRealtimeSignalExecutor.submit(() -> null).get(10, TimeUnit.SECONDS);
	}

	private String csrfToken(String body) {
		Matcher matcher = CSRF_TOKEN_PATTERN.matcher(body);
		assertTrue(matcher.find(), body);
		return matcher.group(1);
	}

	private long eventId(String frame) {
		Matcher matcher = EVENT_ID_PATTERN.matcher(frame);
		assertTrue(matcher.find(), frame);
		return Long.parseLong(matcher.group(1));
	}

	private void assertHttpValidationError(HttpResponse<String> response) {
		assertEquals(400, response.statusCode(), response.body());
		assertTrue(response.body().contains("\"code\":\"VALIDATION_ERROR\""), response.body());
	}

	private String loginBody(String email) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
	}

	private CreateUserAccountCommand command(String email, String nickname) {
		return new CreateUserAccountCommand(
			UserEmail.from(email).orElseThrow(), RawPassword.from(PASSWORD).orElseThrow(),
			UserNickname.from(nickname).orElseThrow());
	}

	private Room createChatRoom(long hostUserId) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"발행 실패 검증 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW.plusSeconds(3600),
				"홍대",
				2));
		roomId = room.getId();
		ChatRoom chatRoom = chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		chatRoomId = chatRoom.getId();
		return room;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		ChatRealtimePublishControl chatRealtimePublishControl() {
			return new ChatRealtimePublishControl();
		}

		@Bean(destroyMethod = "shutdown")
		ExecutorService chatRealtimeSignalExecutor() {
			return Executors.newSingleThreadExecutor();
		}

		@Bean
		@Primary
		ChatRealtimePublisher controlledChatRealtimePublisher(
			ChatRealtimeSignalGateway chatRealtimeSignalGateway,
			ChatRealtimePublishControl chatRealtimePublishControl,
			ExecutorService chatRealtimeSignalExecutor) {
			return event -> {
				chatRealtimePublishControl.recordPublishAttempt();
				if (chatRealtimePublishControl.publishFailureEnabled()) {
					chatRealtimePublishControl.recordFailedPublishAttempt();
					throw new IllegalStateException("redis publish unavailable");
				}
				chatRealtimeSignalExecutor.execute(() -> chatRealtimeSignalGateway.onMessageCommitted(event));
			};
		}
	}

	static final class ChatRealtimePublishControl {

		private final AtomicBoolean publishFailure = new AtomicBoolean();
		private final AtomicInteger publishAttempts = new AtomicInteger();
		private final AtomicInteger failedPublishAttempts = new AtomicInteger();

		void reset() {
			publishFailure.set(false);
			publishAttempts.set(0);
			failedPublishAttempts.set(0);
		}

		void failPublishes() {
			publishFailure.set(true);
		}

		boolean publishFailureEnabled() {
			return publishFailure.get();
		}

		void recordPublishAttempt() {
			publishAttempts.incrementAndGet();
		}

		void recordFailedPublishAttempt() {
			failedPublishAttempts.incrementAndGet();
		}

		int publishAttempts() {
			return publishAttempts.get();
		}

		int failedPublishAttempts() {
			return failedPublishAttempts.get();
		}
	}
}
