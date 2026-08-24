package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T1: 참가 중인 사용자가 실제 PostgreSQL에서 {@code GET /api/users/me/rooms?role=joined}를 호출했을 때 HTTP
 * 200과 올바른 {@code lastMessagePreview}·{@code lastMessageAt}·{@code unreadCount}를 반환하는지, 저장 시각의
 * 마이크로초 정밀도가 손실 없이 반영되는지 실제 HTTP 왕복으로 검증한다(issue #881,
 * https://github.com/bamsongi-club/albam-mate/issues/881#issuecomment-5341884487). 로그인 헬퍼는
 * {@code RoomListPostgresTest}의 실제 로그인 패턴을 따른다.
 */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.security.cookie.secure=false")
class ChatRoomPreviewHttpPostgresTest extends SharedPostgresIntegrationSupport {

	private static final String PASSWORD = "123456789012345";
	private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");
	private static final Instant FUTURE_START_AT = Instant.parse("2099-08-19T00:00:00Z");

	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private UserAccountService userAccountService;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private ObjectMapper objectMapper;
	@LocalServerPort
	private int port;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table chat_room_read_states, chat_messages, chat_rooms, participations, rooms, users restart identity cascade");
	}

	@Test
	void T1_참가_중인_사용자가_실제_HTTP로_role_joined를_호출하면_실제_메시지_미리보기와_미읽음_시각_정밀도가_그대로_반환된다() throws Exception {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		String guestEmail = "chat-preview-http-guest-" + UUID.randomUUID() + "@example.com";
		UserAccount guest = createLoginUser(guestEmail);
		insertActiveParticipation(room.getId(), guest.id());
		Instant microPrecisionCreatedAt = NOW.plusNanos(123_456_000L);
		insertMessage(room.getId(), hostUserId, "호스트가 보낸 실제 메시지", microPrecisionCreatedAt);

		HttpClient guestClient = login(guestEmail);
		HttpResponse<String> response = get(guestClient, "/api/users/me/rooms?role=joined");

		assertEquals(200, response.statusCode(), response.body());
		JsonNode room1 = objectMapper.readTree(response.body()).path("data").path("content").path(0);
		assertEquals(room.getId(), room1.path("id").asLong());
		assertEquals("호스트가 보낸 실제 메시지", room1.path("lastMessagePreview").asText());
		assertEquals(microPrecisionCreatedAt, Instant.parse(room1.path("lastMessageAt").asText()));
		assertEquals(1, room1.path("unreadCount").asInt());
	}

	private void insertMessage(long roomId, long senderUserId, String content, Instant createdAt) {
		long chatRoomInternalId = chatRoomRepository.findByRoomId(roomId).orElseThrow().getId();
		chatMessageRepository.save(
			ChatMessage.create(
				chatRoomInternalId, senderUserId, "preview-http-pg-" + UUID.randomUUID(), content, createdAt));
	}

	private void insertActiveParticipation(long roomId, long userId) {
		OffsetDateTime nowUtc = NOW.atOffset(ZoneOffset.UTC);
		jdbcTemplate.update(
			"insert into participations (room_id, user_id, status, joined_at, created_at, updated_at) "
				+ "values (?, ?, 'ACTIVE', ?, ?, ?)",
			roomId,
			userId,
			nowUtc,
			nowUtc,
			nowUtc);
	}

	private long insertUser(String nickname) {
		String email = "chat-preview-http-pg-" + UUID.randomUUID() + "@example.com";
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'hash', ?, current_timestamp, current_timestamp) returning id",
			Long.class,
			email,
			nickname);
	}

	private Room createChatRoom(long hostUserId) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"PostgreSQL HTTP 미리보기 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				FUTURE_START_AT,
				"홍대",
				4));
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		return room;
	}

	private UserAccount createLoginUser(String email) {
		return userAccountService.createAccount(
			new CreateUserAccountCommand(
				UserEmail.from(email).orElseThrow(),
				RawPassword.from(PASSWORD).orElseThrow(),
				UserNickname.from("채팅 참가자").orElseThrow()));
	}

	private HttpClient login(String email) throws Exception {
		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
		JsonNode csrf = objectMapper.readTree(get(client, "/api/auth/csrf").body()).path("data");
		HttpResponse<String> loginResponse = client.send(
			HttpRequest.newBuilder(uri("/api/auth/login"))
				.header("Content-Type", "application/json")
				.header(csrf.path("headerName").asText(), csrf.path("token").asText())
				.POST(HttpRequest.BodyPublishers.ofString(
					"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}",
					StandardCharsets.UTF_8))
				.build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		assertEquals(200, loginResponse.statusCode(), loginResponse.body());
		assertTrue(cookieManager.getCookieStore().getCookies().stream()
			.anyMatch(cookie -> cookie.getName().equals("JSESSIONID")));
		return client;
	}

	private HttpResponse<String> get(HttpClient client, String path) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri(path)).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}
}
