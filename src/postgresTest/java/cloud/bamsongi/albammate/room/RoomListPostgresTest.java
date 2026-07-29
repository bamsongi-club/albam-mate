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
			"truncate table participations, rooms, users restart identity cascade");
	}

	@Test
	void 사람_중심_검색어_미지정은_공개_목록_응답을_반환한다() throws Exception {
		savePersonRoom("첫 번째 모임");

		HttpResponse<String> response = getRooms("?type=PERSON_FOCUSED");

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"status\":200"));
		assertTrue(response.body().contains("\"data\":{\"content\":"));
		assertTrue(response.body().contains("\"roomType\":\"PERSON_FOCUSED\""));
		assertTrue(response.body().contains("\"title\":\"첫 번째 모임\""));
	}

	@Test
	void 사람_중심_빈_검색어는_검색어_미지정과_같은_목록을_반환한다() throws Exception {
		savePersonRoom("첫 번째 모임");
		savePersonRoom("두 번째 모임");

		HttpResponse<String> withoutKeyword = getRooms("?type=PERSON_FOCUSED");
		HttpResponse<String> emptyKeyword = getRooms("?type=PERSON_FOCUSED&keyword=");

		assertEquals(200, withoutKeyword.statusCode());
		assertEquals(200, emptyKeyword.statusCode());
		assertEquals(withoutKeyword.body(), emptyKeyword.body());
	}

	@Test
	void 사람_중심_검색어는_제목을_대소문자_구분없이_부분_검색한다() throws Exception {
		savePersonRoom("Party Night");
		savePersonRoom("스터디 모임");

		HttpResponse<String> response = getRooms("?type=PERSON_FOCUSED&keyword=party");

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"title\":\"Party Night\""));
		assertFalse(response.body().contains("\"title\":\"스터디 모임\""));
	}

	private void savePersonRoom(String title) {
		roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				title,
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				START_AT,
				"테스트 장소",
				3));
	}

	private HttpResponse<String> getRooms(String query) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(
			URI.create("http://localhost:" + port + "/api/rooms" + query))
			.GET()
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
		return response;
	}
}
