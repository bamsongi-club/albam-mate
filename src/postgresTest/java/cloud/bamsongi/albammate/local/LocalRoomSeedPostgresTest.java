package cloud.bamsongi.albammate.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@ActiveProfiles("local")
class LocalRoomSeedPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String SEED_HOST_EMAIL = "local.seed.host@albammate.local";
	private static final String HOST_MANUAL_ROOM_TITLE = "로컬 사용자의 수동 모임";
	private static final String LAST_PERSON_SEED_TITLE = "뭐 할지 정하기 어려우면";
	private static final long LOCAL_GAME_BGG_ID_BASE = -9_000_000_000L;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_local_seed_test");

	@Autowired
	private Flyway flyway;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void localDatasourceProperties(DynamicPropertyRegistry registry) {
		registry.add("ALBAM_MATE_LOCAL_DB_HOST", postgres::getHost);
		registry.add("ALBAM_MATE_LOCAL_DB_PORT", () -> postgres.getMappedPort(5432));
		registry.add("ALBAM_MATE_LOCAL_DB_NAME", postgres::getDatabaseName);
		registry.add("ALBAM_MATE_LOCAL_DB_USER", postgres::getUsername);
		registry.add("ALBAM_MATE_LOCAL_DB_PASSWORD", postgres::getPassword);
	}

	@Test
	void local_Flyway_콜백은_시드를_복구하고_수동_데이터를_보존한다() {
		assertInitialSeed();

		long manualUserId = insertUser("manual@example.com", "수동 사용자");
		long manualGameId = insertGame(10_001L, "수동 양수 게임");
		long manualRoomId = insertRoom(manualGameId, manualUserId, "수동 모임", "수동 설명", "수동 장소");
		insertParticipation(manualRoomId, manualUserId);
		long seedHostId = userId(SEED_HOST_EMAIL);
		long hostManualRoomId = insertRoom(
			manualGameId,
			seedHostId,
			"로컬 사용자의 수동 모임",
			"호스트 수동 설명",
			"호스트 수동 장소");
		insertParticipation(hostManualRoomId, seedHostId);
		insertGame(-77L, "외부 음수 게임");
		for (int index = 2; index <= 30; index++) {
			insertGame(10_000L + index, "양수 게임 " + index);
		}

		long firstGameRoomId = firstGameFocusedSeedRoomId();
		for (int index = 1; index <= 6; index++) {
			long participantId = insertUser("participant" + index + "@example.com", "참가자" + index);
			insertParticipation(firstGameRoomId, participantId);
		}
		jdbcTemplate.update(
			"""
				update rooms
				set description = '이전 시드 문구', experience_level = 'EXPERIENCED_PREFERRED', is_rulemaster_led = true,
				    region = '강남', capacity = 10, active_participant_count = 6,
				    start_at = ?, place = '이전 장소', status = 'CANCELED'
				where id = ?
				""",
			Timestamp.from(Instant.now().minusSeconds(86_400)),
			firstGameRoomId);
		jdbcTemplate.update("delete from rooms where id = ?", roomId(LAST_PERSON_SEED_TITLE));

		flyway.migrate();

		assertEquals(60, countSeedRooms());
		assertEquals(30, countSeedRooms("GAME_FOCUSED"));
		assertEquals(30, countSeedRooms("PERSON_FOCUSED"));
		assertEquals(30, countSeedGamesWithBggIdBetween(10_001L, 10_030L));
		assertEquals(0, countSeedGamesWithBggId(-77L));
		assertEquals(30, countReservedFallbackGames());
		assertEquals(1, count("select count(*) from rooms where id = " + manualRoomId + " and description = '수동 설명'"));
		assertEquals(1, count("select count(*) from participations where room_id = " + manualRoomId));
		assertEquals(
			1,
			count("select count(*) from rooms where id = " + hostManualRoomId + " and description = '호스트 수동 설명'"));
		assertEquals(1, count("select count(*) from participations where room_id = " + hostManualRoomId));
		assertEquals(
			1,
			count(
				"""
					select count(*)
					from rooms
					where id = %d and room_type = 'GAME_FOCUSED'
					  and title = '오늘 저녁 같이 한 판 하실 분'
					  and description = '가볍게 한두 판 돌리고 이야기 나누는 자리예요.'
					  and experience_level = 'ALL_LEVELS' and not is_rulemaster_led
					  and region = '홍대' and capacity = 6 and active_participant_count = 6
					  and place = '합정역 근처 보드게임 카페' and status = 'CLOSED'
					""".formatted(firstGameRoomId)));
		assertEquals(1, count("select count(*) from rooms where title = '" + LAST_PERSON_SEED_TITLE + "'"));
		assertTrue(minimumSeedStartAt().isAfter(Instant.now()));
	}

	private void assertInitialSeed() {
		assertEquals(30, countSeedRooms("GAME_FOCUSED"));
		assertEquals(30, countSeedRooms("PERSON_FOCUSED"));
		assertEquals(1, count("select count(*) from users where email = '" + SEED_HOST_EMAIL + "'"));
		assertEquals(30, countReservedFallbackGames());
		assertTrue(minimumSeedStartAt().isAfter(Instant.now()));
	}

	private long insertUser(String email, String nickname) {
		jdbcTemplate.update(
			"""
				insert into users (email, password_hash, nickname, created_at, updated_at)
				values (?, 'hash', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""",
			email,
			nickname);
		return userId(email);
	}

	private long insertGame(long bggId, String name) {
		jdbcTemplate.update(
			"""
				insert into games (
				    bgg_id, name, english_name, supported_player_count, tag,
				    estimated_play_time, description, detail_description, created_at, updated_at)
				values (?, ?, ?, '2~4명', '전략', '60분', '설명', '상세 설명', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""",
			bggId,
			name,
			name);
		return jdbcTemplate.queryForObject("select id from games where bgg_id = ?", Long.class, bggId);
	}

	private long insertRoom(long gameId, long hostUserId, String title, String description, String place) {
		jdbcTemplate.update(
			"""
				insert into rooms (
				    game_id, host_user_id, room_type, title, description, experience_level,
				    is_rulemaster_led, region, capacity, active_participant_count, start_at,
				    place, status, version, created_at, updated_at)
				values (?, ?, 'GAME_FOCUSED', ?, ?, 'ALL_LEVELS', false, '홍대', 6, 0,
				        CURRENT_TIMESTAMP + INTERVAL '2 days', ?, 'RECRUITING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""",
			gameId,
			hostUserId,
			title,
			description,
			place);
		return jdbcTemplate.queryForObject(
			"select id from rooms where host_user_id = ? and title = ?",
			Long.class,
			hostUserId,
			title);
	}

	private void insertParticipation(long roomId, long userId) {
		jdbcTemplate.update(
			"""
				insert into participations (room_id, user_id, status, joined_at, created_at, updated_at)
				values (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""",
			roomId,
			userId);
	}

	private long roomId(String title) {
		return jdbcTemplate.queryForObject(
			"""
				select room.id
				from rooms room
				join users host on host.id = room.host_user_id
				where host.email = ? and room.title = ?
				""",
			Long.class,
			SEED_HOST_EMAIL,
			title);
	}

	private long userId(String email) {
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private long firstGameFocusedSeedRoomId() {
		return jdbcTemplate.queryForObject(
			"""
				select room.id
				from rooms room
				join users host on host.id = room.host_user_id
				where host.email = ? and room.title <> ? and room.room_type = 'GAME_FOCUSED'
				order by room.start_at
				limit 1
				""",
			Long.class,
			SEED_HOST_EMAIL,
			HOST_MANUAL_ROOM_TITLE);
	}

	private int countSeedRooms() {
		return countSeedRooms(null);
	}

	private int countSeedRooms(String roomType) {
		String query = """
			select count(*)
			from rooms room
			join users host on host.id = room.host_user_id
			where host.email = ? and room.title <> ?
			""";
		if (roomType == null) {
			return jdbcTemplate.queryForObject(query, Integer.class, SEED_HOST_EMAIL, HOST_MANUAL_ROOM_TITLE);
		}
		return jdbcTemplate.queryForObject(
			query + " and room.room_type = ?",
			Integer.class,
			SEED_HOST_EMAIL,
			HOST_MANUAL_ROOM_TITLE,
			roomType);
	}

	private int countSeedGamesWithBggId(long bggId) {
		return jdbcTemplate.queryForObject(
			"""
				select count(*)
				from rooms room
				join users host on host.id = room.host_user_id
				join games game on game.id = room.game_id
				where host.email = ? and room.title <> ? and game.bgg_id = ?
				""",
			Integer.class,
			SEED_HOST_EMAIL,
			HOST_MANUAL_ROOM_TITLE,
			bggId);
	}

	private int countSeedGamesWithBggIdBetween(long fromBggId, long toBggId) {
		return jdbcTemplate.queryForObject(
			"""
				select count(*)
				from rooms room
				join users host on host.id = room.host_user_id
				join games game on game.id = room.game_id
				where host.email = ? and room.title <> ? and game.bgg_id between ? and ?
				""",
			Integer.class,
			SEED_HOST_EMAIL,
			HOST_MANUAL_ROOM_TITLE,
			fromBggId,
			toBggId);
	}

	private int countReservedFallbackGames() {
		return jdbcTemplate.queryForObject(
			"select count(*) from games where bgg_id between ? and ?",
			Integer.class,
			LOCAL_GAME_BGG_ID_BASE - 30,
			LOCAL_GAME_BGG_ID_BASE - 1);
	}

	private Instant minimumSeedStartAt() {
		return jdbcTemplate.queryForObject(
			"""
				select min(room.start_at)
				from rooms room
				join users host on host.id = room.host_user_id
				where host.email = ? and room.title <> ?
				""",
			Instant.class,
			SEED_HOST_EMAIL,
			HOST_MANUAL_ROOM_TITLE);
	}

	private int count(String query) {
		return jdbcTemplate.queryForObject(query, Integer.class);
	}
}
