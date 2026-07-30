package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoomListPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant START_AT = Instant.parse("2099-01-01T10:00:00Z");
	private static final OffsetDateTime START_AT_UTC = START_AT.atOffset(ZoneOffset.UTC);

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("room_list_test");

	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
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
			"truncate table participations, rooms, games, users restart identity cascade");
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
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				roomType,
				title,
				null,
				gameId,
				ExperienceLevel.ALL_LEVELS,
				false,
				startsAt,
				"테스트 장소",
				3));
		jdbcTemplate.update("update rooms set status = ? where id = ?", status.name(), room.getId());
		return room.getId();
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
		HttpRequest request = HttpRequest.newBuilder(
			URI.create("http://localhost:" + port + "/api/rooms" + query))
			.GET()
			.build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}
}
