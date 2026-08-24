package cloud.bamsongi.albammate.chat.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.repository.UserRepository;

/** T1·T2·T3: 실제 WebSocket handshake로 세션·Origin·방 접근 판정과 세션 무효화 뒤 거절을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.security.cookie.secure=false")
class ChatWebSocketHandshakeIntegrationTest {

	private static final Instant FUTURE_STARTS_AT = Instant.parse("2099-01-01T10:00:00Z");
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";
	private static final String PASSWORD = "123456789012345";
	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

	@LocalServerPort
	private int port;

	@Autowired
	private UserAccountService userAccountService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final List<Long> roomIds = new ArrayList<>();
	private final List<Long> userIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		for (Long roomId : roomIds) {
			for (Long userId : userIds) {
				participationRepository.findByRoomIdAndUserId(roomId, userId)
					.ifPresent(participationRepository::delete);
			}
			chatRoomRepository.findByRoomId(roomId).ifPresent(chatRoomRepository::delete);
			jdbcTemplate.update("delete from notification_outbox_events where room_id = ?", roomId);
			roomRepository.deleteById(roomId);
		}
		userIds.forEach(userRepository::deleteById);
	}

	@Test
	void 기존_세션과_허용된_Origin을_가진_주최자와_참가자는_handshake에_성공한다() throws Exception {
		TestAccount host = signup("주최자");
		TestAccount participant = signup("참가자");
		Room room = createChatRoom(host.userId());
		roomParticipationService.participate(participant.userId(), room.getId());

		HttpCookie hostSession = login(host.email());
		HttpCookie participantSession = login(participant.email());

		assertHandshakeSucceeds(room.getId(), hostSession.getValue(), ALLOWED_ORIGIN);
		assertHandshakeSucceeds(room.getId(), participantSession.getValue(), ALLOWED_ORIGIN);
	}

	@Test
	void 다른_방_비관계자와_최종_상태_방의_handshake는_거절된다() throws Exception {
		TestAccount host = signup("주최자");
		TestAccount stranger = signup("비관계자");
		Room activeRoom = createChatRoom(host.userId());
		Room canceledRoom = createChatRoom(host.userId());
		assertTrue(canceledRoom.cancel());
		roomRepository.saveAndFlush(canceledRoom);

		HttpCookie hostSession = login(host.email());
		HttpCookie strangerSession = login(stranger.email());

		assertHandshakeRejected(999_999L, hostSession.getValue(), ALLOWED_ORIGIN, 404);
		assertHandshakeRejected(activeRoom.getId(), strangerSession.getValue(), ALLOWED_ORIGIN, 403);
		assertHandshakeRejected(canceledRoom.getId(), hostSession.getValue(), ALLOWED_ORIGIN, 403);
	}

	@Test
	void 세션이_무효화된_뒤_재사용한_JSESSIONID는_handshake가_거절된다() throws Exception {
		TestAccount host = signup("주최자");
		Room room = createChatRoom(host.userId());
		URI baseUri = URI.create("http://localhost:" + port);
		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();

		HttpResponse<String> csrf = get(client, baseUri.resolve("/api/auth/csrf"));
		HttpResponse<String> loginResponse = post(
			client,
			baseUri.resolve("/api/auth/login"),
			loginBody(host.email()),
			csrfToken(csrf.body()));
		assertEquals(200, loginResponse.statusCode());
		String sessionId = cookieNamed(cookieManager, "JSESSIONID").getValue();

		assertHandshakeSucceeds(room.getId(), sessionId, ALLOWED_ORIGIN);

		HttpResponse<String> refreshedCsrf = get(client, baseUri.resolve("/api/auth/csrf"));
		HttpResponse<String> logout = post(
			client, baseUri.resolve("/api/auth/logout"), "", csrfToken(refreshedCsrf.body()));
		assertEquals(200, logout.statusCode());

		assertHandshakeRejected(room.getId(), sessionId, ALLOWED_ORIGIN, 401);
	}

	private void assertHandshakeSucceeds(long roomId, String sessionId, String origin) throws Exception {
		WebSocket webSocket = connect(roomId, sessionId, origin);
		try {
			assertTrue(!webSocket.isOutputClosed());
		} finally {
			webSocket.abort();
		}
	}

	private void assertHandshakeRejected(long roomId, String sessionId, String origin, int expectedStatus) {
		ExecutionException exception = assertThrows(
			ExecutionException.class, () -> connect(roomId, sessionId, origin));
		WebSocketHandshakeException handshakeException = assertInstanceOf(
			WebSocketHandshakeException.class, exception.getCause());
		assertEquals(expectedStatus, handshakeException.getResponse().statusCode());
	}

	private WebSocket connect(long roomId, String sessionId, String origin) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		URI wsUri = URI.create("ws://localhost:" + port + "/api/rooms/" + roomId + "/chat/ws");
		return client
			.newWebSocketBuilder()
			.header("Cookie", "JSESSIONID=" + sessionId)
			.header("Origin", origin)
			.buildAsync(wsUri, new WebSocket.Listener() {})
			.get(10, TimeUnit.SECONDS);
	}

	private TestAccount signup(String nickname) {
		String email = "chat-ws-" + UUID.randomUUID() + "@example.com";
		long userId = userAccountService.createAccount(command(email, nickname)).id();
		userIds.add(userId);
		return new TestAccount(userId, email);
	}

	private record TestAccount(long userId, String email) {
	}

	private HttpCookie login(String email) throws Exception {
		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
		URI baseUri = URI.create("http://localhost:" + port);
		HttpResponse<String> csrf = get(client, baseUri.resolve("/api/auth/csrf"));
		HttpResponse<String> loginResponse = post(
			client, baseUri.resolve("/api/auth/login"), loginBody(email), csrfToken(csrf.body()));
		assertEquals(200, loginResponse.statusCode());
		return cookieNamed(cookieManager, "JSESSIONID");
	}

	private String loginBody(String email) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
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

	private HttpCookie cookieNamed(CookieManager cookieManager, String name) {
		return cookieManager
			.getCookieStore()
			.getCookies()
			.stream()
			.filter(cookie -> name.equals(cookie.getName()))
			.findFirst()
			.orElseThrow();
	}

	private String csrfToken(String body) {
		Matcher matcher = CSRF_TOKEN_PATTERN.matcher(body);
		assertTrue(matcher.find(), body);
		return matcher.group(1);
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
				"WS handshake 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				FUTURE_STARTS_AT,
				"홍대",
				2));
		roomIds.add(room.getId());
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		return room;
	}
}
