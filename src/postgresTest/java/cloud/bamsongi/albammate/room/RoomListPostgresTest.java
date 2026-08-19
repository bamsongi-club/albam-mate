package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.security.cookie.secure=false")
class RoomListPostgresTest extends SharedPostgresIntegrationSupport {

	private static final String PASSWORD = "123456789012345";
	private static final Instant START_AT = Instant.parse("2099-01-01T10:00:00Z");
	private static final OffsetDateTime START_AT_UTC = START_AT.atOffset(ZoneOffset.UTC);

	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private UserAccountService userAccountService;
	@Autowired
	private ObjectMapper objectMapper;
	@LocalServerPort
	private int port;

	private Long hostUserId;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update(
			"""
				insert into users (email, password_hash, nickname, created_at, updated_at)
				values ('room-list-postgres-host@example.com', 'hash', '방장', ?, ?)
				""",
			START_AT_UTC,
			START_AT_UTC);
		hostUserId = jdbcTemplate.queryForObject(
			"select id from users where email = 'room-list-postgres-host@example.com'",
			Long.class);
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table room_waitlists, participations, rooms, games, users restart identity cascade");
	}

	@Test
	void 실제_WAITING_사용자와_비WAITING_사용자는_닫힌_만석_방에서_서로_다른_대기_가능_여부를_받는다() throws Exception {
		UserAccount waitingUser = createLoginUser("room-waitlist-existing@example.com");
		createLoginUser("room-waitlist-requester@example.com");
		Long roomId = saveRoom(
			RoomType.PERSON_FOCUSED,
			"대기 가능 닫힌 방",
			null,
			START_AT,
			RoomStatus.CLOSED,
			ExperienceLevel.ALL_LEVELS,
			false,
			3,
			3);
		insertWaiting(roomId, waitingUser.id());

		HttpClient waitingClient = login("room-waitlist-existing@example.com");
		HttpClient requesterClient = login("room-waitlist-requester@example.com");
		JsonNode waitingListRoom = roomFromList(getRooms(waitingClient, ""));
		JsonNode waitingDetailRoom = roomFromDetail(get(waitingClient, "/api/rooms/" + roomId));
		JsonNode requesterListRoom = roomFromList(getRooms(requesterClient, ""));
		JsonNode requesterDetailRoom = roomFromDetail(get(requesterClient, "/api/rooms/" + roomId));

		assertFalse(waitingListRoom.path("waitlistable").asBoolean());
		assertFalse(waitingDetailRoom.path("waitlistable").asBoolean());
		assertTrue(requesterListRoom.path("waitlistable").asBoolean());
		assertTrue(requesterDetailRoom.path("waitlistable").asBoolean());
	}

	@Test
	void 한_ROOM의_minRemainingSeats_SQL_포함_결과는_Room_잔여석_계산과_세_경계값에서_같다() throws Exception {
		Long roomId = saveRoom(
			RoomType.PERSON_FOCUSED,
			"임계값 방",
			null,
			START_AT,
			RoomStatus.RECRUITING,
			ExperienceLevel.ALL_LEVELS,
			false,
			5,
			2);
		int remainingSeats = roomRepository.findById(roomId).orElseThrow().getRemainingRecruitmentSeats();

		assertEquals(3, remainingSeats);
		assertTitlePresent(getRooms("?minRemainingSeats=" + (remainingSeats - 1)).body(), "임계값 방");
		assertTitlePresent(getRooms("?minRemainingSeats=" + remainingSeats).body(), "임계값 방");
		assertTitleAbsent(getRooms("?minRemainingSeats=" + (remainingSeats + 1)).body(), "임계값 방");
	}

	private JsonNode roomFromList(HttpResponse<String> response) throws Exception {
		assertEquals(200, response.statusCode());
		return objectMapper.readTree(response.body())
			.path("data")
			.path("content")
			.path(0);
	}

	private JsonNode roomFromDetail(HttpResponse<String> response) throws Exception {
		assertEquals(200, response.statusCode());
		return objectMapper.readTree(response.body()).path("data");
	}

	@Test
	void 필터없는_목록은_두_유형의_공개_상태만_정렬과_페이지에_따라_반환한다() throws Exception {
		Long gameId = saveGame("첫 번째 게임", 1001L);
		Long firstRoomId = saveRoom(
			RoomType.GAME_FOCUSED, "첫 번째 게임방", gameId, START_AT, RoomStatus.RECRUITING);
		Long secondRoomId = saveRoom(
			RoomType.PERSON_FOCUSED,
			"두 번째 동일 시각 사람방",
			null,
			START_AT,
			RoomStatus.RECRUITING);
		saveRoom(
			RoomType.GAME_FOCUSED,
			"세 번째 마감 게임방",
			gameId,
			START_AT.plusSeconds(60),
			RoomStatus.CLOSED);
		saveRoom(
			RoomType.PERSON_FOCUSED,
			"취소된 사람방",
			null,
			START_AT.plusSeconds(120),
			RoomStatus.CANCELED);
		saveRoom(
			RoomType.PERSON_FOCUSED,
			"종료된 사람방",
			null,
			START_AT.plusSeconds(180),
			RoomStatus.FINISHED);

		HttpResponse<String> firstPage = getRooms("?size=2");
		HttpResponse<String> secondPage = getRooms("?page=1&size=2");
		String firstPageBody = firstPage.body();
		String secondPageBody = secondPage.body();

		assertEquals(200, firstPage.statusCode());
		assertTitlePresent(firstPageBody, "첫 번째 게임방");
		assertTitlePresent(firstPageBody, "두 번째 동일 시각 사람방");
		assertTitleAbsent(firstPageBody, "세 번째 마감 게임방");
		assertTrue(firstPageBody.indexOf("첫 번째 게임방") < firstPageBody.indexOf("두 번째 동일 시각 사람방"));
		assertTrue(
			firstPageBody.indexOf("\"id\":" + firstRoomId) < firstPageBody.indexOf("\"id\":" + secondRoomId));
		assertTrue(firstPageBody.contains("\"roomType\":\"GAME_FOCUSED\""));
		assertTrue(firstPageBody.contains("\"roomType\":\"PERSON_FOCUSED\""));
		assertTrue(firstPageBody.contains("\"status\":\"RECRUITING\""));
		assertEquals(200, secondPage.statusCode());
		assertTitlePresent(secondPageBody, "세 번째 마감 게임방");
		assertTitleAbsent(secondPageBody, "취소된 사람방");
		assertTitleAbsent(secondPageBody, "종료된 사람방");
		assertTrue(secondPageBody.contains("\"status\":\"CLOSED\""));
		assertTrue(firstPageBody.contains("\"totalElements\":3"));
	}

	@Test
	void 독립_필터와_AND_조합은_전달된_조건만_모두_적용한다() throws Exception {
		Long targetGameId = saveGame("대상 게임", 1002L);
		Long otherGameId = saveGame("다른 게임", 1003L);
		saveRoom(
			RoomType.PERSON_FOCUSED,
			"Person Party",
			null,
			START_AT,
			RoomStatus.RECRUITING);
		saveRoom(
			RoomType.PERSON_FOCUSED,
			"Person Target Party",
			targetGameId,
			START_AT.plusSeconds(60),
			RoomStatus.RECRUITING);
		saveRoom(
			RoomType.GAME_FOCUSED,
			"Game Match Party",
			targetGameId,
			START_AT.plusSeconds(120),
			RoomStatus.CLOSED);
		saveRoom(
			RoomType.GAME_FOCUSED,
			"Other Game Room",
			otherGameId,
			START_AT.plusSeconds(180),
			RoomStatus.RECRUITING);

		assertTitles(
			getRooms("?type=PERSON_FOCUSED").body(),
			List.of("Person Party", "Person Target Party"),
			List.of("Game Match Party", "Other Game Room"));
		HttpResponse<String> gameFocused = getRooms("?type=GAME_FOCUSED");
		assertEquals(200, gameFocused.statusCode());
		assertTitles(
			gameFocused.body(),
			List.of("Game Match Party", "Other Game Room"),
			List.of("Person Party", "Person Target Party"));
		assertTitles(
			getRooms("?gameId=" + targetGameId).body(),
			List.of("Person Target Party", "Game Match Party"),
			List.of("Person Party", "Other Game Room"));
		assertTitles(
			getRooms("?keyword=party").body(),
			List.of("Person Party", "Person Target Party", "Game Match Party"),
			List.of("Other Game Room"));
		assertTitles(
			getRooms("?type=PERSON_FOCUSED&gameId=" + targetGameId).body(),
			List.of("Person Target Party"),
			List.of("Person Party", "Game Match Party", "Other Game Room"));
		assertTitles(
			getRooms("?type=GAME_FOCUSED&keyword=match").body(),
			List.of("Game Match Party"),
			List.of("Person Party", "Person Target Party", "Other Game Room"));
		assertTitles(
			getRooms("?type=PERSON_FOCUSED&gameId=" + targetGameId + "&keyword=target").body(),
			List.of("Person Target Party"),
			List.of("Person Party", "Game Match Party", "Other Game Room"));
	}

	@Test
	void status_필터는_공개_범위_안에서만_페이지네이션_전에_적용된다() throws Exception {
		saveRoom(RoomType.PERSON_FOCUSED, "모집 중인 방", null, START_AT, RoomStatus.RECRUITING);
		saveRoom(
			RoomType.PERSON_FOCUSED, "마감된 방", null, START_AT.plusSeconds(60), RoomStatus.CLOSED);
		saveRoom(
			RoomType.PERSON_FOCUSED, "취소된 방", null, START_AT.plusSeconds(120), RoomStatus.CANCELED);
		saveRoom(
			RoomType.PERSON_FOCUSED, "종료된 방", null, START_AT.plusSeconds(180), RoomStatus.FINISHED);

		assertTitles(
			getRooms("?status=RECRUITING").body(),
			List.of("모집 중인 방"),
			List.of("마감된 방", "취소된 방", "종료된 방"));
		assertTitles(
			getRooms("?status=CLOSED").body(),
			List.of("마감된 방"),
			List.of("모집 중인 방", "취소된 방", "종료된 방"));

		HttpResponse<String> canceledFilter = getRooms("?status=CANCELED");
		HttpResponse<String> finishedFilter = getRooms("?status=FINISHED");

		assertEquals(200, canceledFilter.statusCode());
		assertTrue(canceledFilter.body().contains("\"totalElements\":0"));
		assertEquals(200, finishedFilter.statusCode());
		assertTrue(finishedFilter.body().contains("\"totalElements\":0"));
	}

	@Test
	void status_필터는_다른_조건과_함께_페이지_경계에서도_전체_건수와_content를_일치시킨다() throws Exception {
		Long firstRoomId = saveRoom(
			RoomType.PERSON_FOCUSED, "첫 번째 모집 중인 방", null, START_AT, RoomStatus.RECRUITING);
		Long secondRoomId = saveRoom(
			RoomType.PERSON_FOCUSED,
			"두 번째 모집 중인 방",
			null,
			START_AT.plusSeconds(60),
			RoomStatus.RECRUITING);
		saveRoom(
			RoomType.PERSON_FOCUSED, "마감된 방", null, START_AT.plusSeconds(120), RoomStatus.CLOSED);

		HttpResponse<String> firstPage = getRooms("?status=RECRUITING&size=1");
		HttpResponse<String> secondPage = getRooms("?status=RECRUITING&page=1&size=1");

		assertEquals(200, firstPage.statusCode());
		assertTitlePresent(firstPage.body(), "첫 번째 모집 중인 방");
		assertTitleAbsent(firstPage.body(), "두 번째 모집 중인 방");
		assertTitleAbsent(firstPage.body(), "마감된 방");
		assertTrue(firstPage.body().contains("\"totalElements\":2"));
		assertTrue(firstPage.body().indexOf("\"id\":" + firstRoomId) >= 0);
		assertEquals(200, secondPage.statusCode());
		assertTitlePresent(secondPage.body(), "두 번째 모집 중인 방");
		assertTitleAbsent(secondPage.body(), "첫 번째 모집 중인 방");
		assertTrue(secondPage.body().indexOf("\"id\":" + secondRoomId) >= 0);
	}

	@Test
	void 빈_검색어는_검색어_미지정과_같고_제목_부분_일치는_대소문자를_구분하지_않는다() throws Exception {
		saveRoom(RoomType.PERSON_FOCUSED, "Party Night", null, START_AT, RoomStatus.RECRUITING);
		saveRoom(
			RoomType.PERSON_FOCUSED,
			"스터디 모임",
			null,
			START_AT.plusSeconds(60),
			RoomStatus.RECRUITING);

		HttpResponse<String> withoutKeyword = getRooms("");
		HttpResponse<String> emptyKeyword = getRooms("?keyword=");
		HttpResponse<String> blankKeyword = getRooms("?keyword=%20%20");
		String searchedBody = getRooms("?keyword=party").body();

		assertEquals(200, withoutKeyword.statusCode());
		assertEquals(withoutKeyword.body(), emptyKeyword.body());
		assertEquals(withoutKeyword.body(), blankKeyword.body());
		assertTitlePresent(searchedBody, "Party Night");
		assertTitleAbsent(searchedBody, "스터디 모임");
	}

	@Test
	void P1_조건은_SQL에서_AND로_적용하고_경험수준만_OR로_정렬과_페이지를_계산한다() throws Exception {
		Long firstTargetId = saveRoom(
			RoomType.PERSON_FOCUSED,
			"첫 번째 대상 모임",
			null,
			START_AT,
			RoomStatus.RECRUITING,
			ExperienceLevel.BEGINNER_WELCOME,
			true,
			3,
			1);
		insertActiveParticipation(firstTargetId, "first-target-participant@example.com");
		Long secondTargetId = saveRoom(
			RoomType.PERSON_FOCUSED,
			"두 번째 대상 모임",
			null,
			START_AT.plusSeconds(60),
			RoomStatus.RECRUITING,
			ExperienceLevel.ALL_LEVELS,
			true,
			3,
			0);
		saveRoom(
			RoomType.PERSON_FOCUSED,
			"경험자 권장 대상 모임",
			null,
			START_AT.plusSeconds(50),
			RoomStatus.RECRUITING,
			ExperienceLevel.EXPERIENCED_PREFERRED,
			true,
			3,
			0);
		saveRoom(
			RoomType.PERSON_FOCUSED,
			"종료 경계 모임",
			null,
			START_AT.plusSeconds(120),
			RoomStatus.RECRUITING,
			ExperienceLevel.BEGINNER_WELCOME,
			true,
			3,
			0);
		saveRoom(
			RoomType.PERSON_FOCUSED,
			"남은 자리 부족 모임",
			null,
			START_AT.plusSeconds(30),
			RoomStatus.RECRUITING,
			ExperienceLevel.BEGINNER_WELCOME,
			true,
			3,
			2);
		saveRoom(
			RoomType.PERSON_FOCUSED,
			"룰마스터 없음 모임",
			null,
			START_AT.plusSeconds(40),
			RoomStatus.RECRUITING,
			ExperienceLevel.BEGINNER_WELCOME,
			false,
			3,
			0);
		saveRoom(
			RoomType.PERSON_FOCUSED,
			"취소 조건 충족 모임",
			null,
			START_AT.plusSeconds(90),
			RoomStatus.CANCELED,
			ExperienceLevel.BEGINNER_WELCOME,
			true,
			3,
			0);
		saveRoom(
			RoomType.PERSON_FOCUSED,
			"종료 조건 충족 모임",
			null,
			START_AT.plusSeconds(100),
			RoomStatus.FINISHED,
			ExperienceLevel.BEGINNER_WELCOME,
			true,
			3,
			0);

		String filter = "?type=PERSON_FOCUSED&keyword=모임"
			+ "&startsAtFrom=" + START_AT
			+ "&startsAtTo=" + START_AT.plusSeconds(120)
			+ "&minRemainingSeats=2&experienceLevels=BEGINNER_WELCOME"
			+ "&experienceLevels=ALL_LEVELS&experienceLevels=BEGINNER_WELCOME&rulemasterOnly=true";
		HttpResponse<String> firstPage = getRooms(filter + "&size=1");
		HttpResponse<String> secondPage = getRooms(filter + "&page=1&size=1");
		HttpResponse<String> withoutRulemasterFilter = getRooms(
			"?startsAtFrom=" + START_AT
				+ "&startsAtTo=" + START_AT.plusSeconds(120)
				+ "&minRemainingSeats=2&experienceLevels=BEGINNER_WELCOME&rulemasterOnly=false");
		HttpResponse<String> withoutExperienceLevelFilter = getRooms(
			"?type=PERSON_FOCUSED&keyword=모임"
				+ "&startsAtFrom=" + START_AT
				+ "&startsAtTo=" + START_AT.plusSeconds(120)
				+ "&minRemainingSeats=2&rulemasterOnly=true");

		assertEquals(200, firstPage.statusCode());
		assertTrue(firstPage.body().contains("\"totalElements\":2"));
		assertTitlePresent(firstPage.body(), "첫 번째 대상 모임");
		assertTitleAbsent(firstPage.body(), "두 번째 대상 모임");
		assertEquals(200, secondPage.statusCode());
		assertTitlePresent(secondPage.body(), "두 번째 대상 모임");
		assertTitleAbsent(secondPage.body(), "첫 번째 대상 모임");
		assertTitleAbsent(firstPage.body(), "종료 경계 모임");
		assertTitleAbsent(firstPage.body(), "경험자 권장 대상 모임");
		assertTitleAbsent(firstPage.body(), "남은 자리 부족 모임");
		assertTitleAbsent(firstPage.body(), "룰마스터 없음 모임");
		assertTitleAbsent(firstPage.body(), "취소 조건 충족 모임");
		assertTitleAbsent(firstPage.body(), "종료 조건 충족 모임");
		assertTitlePresent(withoutRulemasterFilter.body(), "룰마스터 없음 모임");
		assertEquals(200, withoutExperienceLevelFilter.statusCode());
		assertTitlePresent(withoutExperienceLevelFilter.body(), "경험자 권장 대상 모임");
	}

	@Test
	void 유효_세션의_ACTIVE_참가자는_필터없는_실제_목록에서_참가할_수_없다() throws Exception {
		String participantEmail = "room-list-session-participant@example.com";
		UserAccount participant = createLoginUser(participantEmail);
		Long joinedRoomId = saveRoom(
			RoomType.PERSON_FOCUSED,
			"세션 참가 방",
			null,
			START_AT,
			RoomStatus.RECRUITING);
		insertActiveParticipation(joinedRoomId, participant.id());
		jdbcTemplate.update("update rooms set active_participant_count = 1 where id = ?", joinedRoomId);
		saveRoom(
			RoomType.PERSON_FOCUSED,
			"세션 다음 공개 방",
			null,
			START_AT.plusSeconds(60),
			RoomStatus.RECRUITING);
		saveRoom(
			RoomType.PERSON_FOCUSED,
			"세션 제외 취소 방",
			null,
			START_AT.plusSeconds(120),
			RoomStatus.CANCELED);

		HttpClient client = login(participantEmail);
		HttpResponse<String> firstPage = getRooms(client, "?size=1");
		HttpResponse<String> secondPage = getRooms(client, "?page=1&size=1");

		assertEquals(200, firstPage.statusCode());
		assertTrue(firstPage.body().contains("\"page\":0"));
		assertTrue(firstPage.body().contains("\"size\":1"));
		assertTrue(firstPage.body().contains("\"totalElements\":2"));
		assertTitlePresent(firstPage.body(), "세션 참가 방");
		assertTrue(firstPage.body().contains("\"joinable\":false"));
		assertTitleAbsent(firstPage.body(), "세션 제외 취소 방");
		assertEquals(200, secondPage.statusCode());
		assertTitlePresent(secondPage.body(), "세션 다음 공개 방");
		assertTitleAbsent(secondPage.body(), "세션 참가 방");
		assertTrue(secondPage.body().contains("\"joinable\":true"));
	}

	private Long saveGame(String name, long bggId) {
		jdbcTemplate.update(
			"""
				insert into games (
				    bgg_id, name, english_name, supported_player_count, tag,
				    estimated_play_time, description, detail_description, created_at, updated_at)
				values (?, ?, ?, '2~4명', '전략', '60분', '설명', '상세 설명', ?, ?)
				""",
			bggId,
			name,
			name,
			START_AT_UTC,
			START_AT_UTC);
		return jdbcTemplate.queryForObject("select id from games where bgg_id = ?", Long.class, bggId);
	}

	private Long saveRoom(
		RoomType roomType, String title, Long gameId, Instant startsAt, RoomStatus status) {
		return saveRoom(
			roomType,
			title,
			gameId,
			startsAt,
			status,
			ExperienceLevel.ALL_LEVELS,
			false,
			3,
			0);
	}

	private Long saveRoom(
		RoomType roomType,
		String title,
		Long gameId,
		Instant startsAt,
		RoomStatus status,
		ExperienceLevel experienceLevel,
		boolean rulemasterLed,
		int capacity,
		int activeParticipantCount) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				roomType,
				title,
				null,
				gameId,
				experienceLevel,
				rulemasterLed,
				startsAt,
				"테스트 장소",
				capacity));
		jdbcTemplate.update(
			"update rooms set status = ?, active_participant_count = ? where id = ?",
			status.name(),
			activeParticipantCount,
			room.getId());
		return room.getId();
	}

	private void insertActiveParticipation(long roomId, String email) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', '참가자', ?, ?)",
			email,
			START_AT_UTC,
			START_AT_UTC);
		Long userId = jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
		jdbcTemplate.update(
			"insert into participations (room_id, user_id, status, joined_at, created_at, updated_at) "
				+ "values (?, ?, 'ACTIVE', ?, ?, ?)",
			roomId,
			userId,
			START_AT_UTC,
			START_AT_UTC,
			START_AT_UTC);
	}

	private void insertActiveParticipation(long roomId, long userId) {
		jdbcTemplate.update(
			"insert into participations (room_id, user_id, status, joined_at, created_at, updated_at) "
				+ "values (?, ?, 'ACTIVE', ?, ?, ?)",
			roomId,
			userId,
			START_AT_UTC,
			START_AT_UTC,
			START_AT_UTC);
	}

	private void insertWaiting(long roomId, long userId) {
		jdbcTemplate.update(
			"""
				insert into room_waitlists (
				    room_id, user_id, status, queue_order, queued_at, created_at, updated_at)
				values (?, ?, 'WAITING', nextval('room_waitlist_queue_order_seq'), ?, ?, ?)
				""",
			roomId,
			userId,
			START_AT_UTC,
			START_AT_UTC,
			START_AT_UTC);
	}

	private UserAccount createLoginUser(String email) {
		return userAccountService.createAccount(
			new CreateUserAccountCommand(
				UserEmail.from(email).orElseThrow(),
				RawPassword.from(PASSWORD).orElseThrow(),
				UserNickname.from("세션 참가자").orElseThrow()));
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

	private void assertTitles(String body, List<String> expectedTitles, List<String> excludedTitles) {
		expectedTitles.forEach(title -> assertTitlePresent(body, title));
		excludedTitles.forEach(title -> assertTitleAbsent(body, title));
	}

	private void assertTitlePresent(String body, String title) {
		assertTrue(body.contains("\"title\":\"" + title + "\""));
	}

	private void assertTitleAbsent(String body, String title) {
		assertFalse(body.contains("\"title\":\"" + title + "\""));
	}

	private HttpResponse<String> getRooms(String query) throws Exception {
		return getRooms(HttpClient.newHttpClient(), query);
	}

	private HttpResponse<String> getRooms(HttpClient client, String query) throws Exception {
		return get(client, "/api/rooms" + query);
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
