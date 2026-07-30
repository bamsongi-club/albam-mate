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
	private static final String SEED_TITLE_PREFIX = "[LOCAL] %";
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

		long firstGameRoomId = roomId("[LOCAL] 게임 중심 모임 01");
		for (int index = 1; index <= 6; index++) {
			long participantId = insertUser("participant" + index + "@example.com", "참가자" + index);
			insertParticipation(firstGameRoomId, participantId);
		}
		jdbcTemplate.update(
			"""
				update rooms
				set description = '이전 시드 문구', experience_level = 'ALL_LEVELS', is_rulemaster_led = false,
				    region = '강남', capacity = 10, active_participant_count = 6,
				    start_at = ?, place = '이전 장소', status = 'CANCELED'
				where id = ?
				""",
			Timestamp.from(Instant.now().minusSeconds(86_400)),
			firstGameRoomId);
		jdbcTemplate.update("delete from rooms where id = ?", roomId("[LOCAL] 사람 중심 모임 30"));

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
					  and description = '로컬 개발용 게임 중심 모임입니다. 편하게 참여해 보세요.'
					  and experience_level = 'BEGINNER_WELCOME' and is_rulemaster_led
					  and region = '홍대' and capacity = 6 and active_participant_count = 6
					  and place = '홍대입구역 보드게임 카페' and status = 'CLOSED'
					""".formatted(firstGameRoomId)));
		assertEquals(1, count("select count(*) from rooms where title = '[LOCAL] 사람 중심 모임 30'"));
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

	private int countSeedRooms() {
		return countSeedRooms(null);
	}

	private int countSeedRooms(String roomType) {
		String query = """
			select count(*)
			from rooms room
			join users host on host.id = room.host_user_id
			where host.email = ? and room.title like ?
			""";
		if (roomType == null) {
			return jdbcTemplate.queryForObject(query, Integer.class, SEED_HOST_EMAIL, SEED_TITLE_PREFIX);
		}
		return jdbcTemplate.queryForObject(
			query + " and room.room_type = ?",
			Integer.class,
			SEED_HOST_EMAIL,
			SEED_TITLE_PREFIX,
			roomType);
	}

	private int countSeedGamesWithBggId(long bggId) {
		return jdbcTemplate.queryForObject(
			"""
				select count(*)
				from rooms room
				join users host on host.id = room.host_user_id
				join games game on game.id = room.game_id
				where host.email = ? and room.title like ? and game.bgg_id = ?
				""",
			Integer.class,
			SEED_HOST_EMAIL,
			SEED_TITLE_PREFIX,
			bggId);
	}

	private int countSeedGamesWithBggIdBetween(long fromBggId, long toBggId) {
		return jdbcTemplate.queryForObject(
			"""
				select count(*)
				from rooms room
				join users host on host.id = room.host_user_id
				join games game on game.id = room.game_id
				where host.email = ? and room.title like ? and game.bgg_id between ? and ?
				""",
			Integer.class,
			SEED_HOST_EMAIL,
			SEED_TITLE_PREFIX,
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
				where host.email = ? and room.title like ?
				""",
			Instant.class,
			SEED_HOST_EMAIL,
			SEED_TITLE_PREFIX);
	}

	private int count(String query) {
		return jdbcTemplate.queryForObject(query, Integer.class);
	}
}
