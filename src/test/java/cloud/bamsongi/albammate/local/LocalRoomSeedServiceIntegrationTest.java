package cloud.bamsongi.albammate.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class LocalRoomSeedServiceIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");
	private static final String SEED_HOST_EMAIL = "local.seed.host@albammate.local";
	private static final String SEED_TITLE_PREFIX = "[LOCAL] %";
	private static final long LOCAL_GAME_BGG_ID_BASE = -9_000_000_000L;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void nonlocal_프로필에서는_시더_빈을_만들지_않는다() {
		assertTrue(applicationContext.getBeansOfType(LocalRoomSeedInitializer.class).isEmpty());
	}

	@Test
	void 시더는_식별가능한_방_60개를_만들고_수동_데이터를_보존한다() {
		long manualUserId = insertUser("manual@example.com", "수동 사용자");
		long manualGameId = insertGame(1L, "수동 게임");
		long manualRoomId = insertRoom(manualGameId, manualUserId, "수동 모임", "수동 설명", NOW.plusSeconds(86_400));
		insertParticipation(manualRoomId, manualUserId);
		insertGame(-77L, "다른 음수 게임");

		seed().seed();

		assertEquals(30, countSeedRooms("GAME_FOCUSED"));
		assertEquals(30, countSeedRooms("PERSON_FOCUSED"));
		assertEquals(1, count("select count(*) from users where email = '" + SEED_HOST_EMAIL + "'"));
		assertEquals(29, countReservedFallbackGames());
		assertEquals(0, countSeedRoomsWithGame(-77L));
		assertEquals(0, countSeedRoomsWithInternalDescription());
		assertEquals(30, countSeedRoomsWithDescription("로컬 개발용 게임 중심 모임입니다. 편하게 참여해 보세요."));
		assertEquals(30, countSeedRoomsWithDescription("로컬 개발용 사람 중심 모임입니다. 게임은 현장에서 함께 정해요."));
		assertEquals(1, count("select count(*) from rooms where title = '수동 모임'"));
		assertEquals(1, count("select count(*) from participations where room_id = " + manualRoomId));
	}

	@Test
	void 재실행은_기존_시드만_갱신하고_삭제된_시드를_복구한다() {
		seed().seed();
		jdbcTemplate.update(
			"""
				update rooms set start_at = ?, status = 'CANCELED', description = '이전 시드 문구'
				where host_user_id = (select id from users where email = ?) and title like ?
				""",
			NOW.minusSeconds(86_400),
			SEED_HOST_EMAIL,
			SEED_TITLE_PREFIX);
		jdbcTemplate.update(
			"delete from rooms where host_user_id = (select id from users where email = ?) and title = ?",
			SEED_HOST_EMAIL,
			"[LOCAL] 사람 중심 모임 30");

		seed().seed();

		assertEquals(60, countSeedRooms());
		assertEquals(
			60,
			countSeedRoomsMatching("and room.start_at > '2026-07-29T09:00:00Z'"));
		assertEquals(
			60,
			countSeedRoomsMatching("and room.status = 'RECRUITING'"));
		assertEquals(0, countSeedRoomsWithInternalDescription());
	}

	private LocalRoomSeedService seed() {
		return new LocalRoomSeedService(jdbcTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private long insertUser(String email, String nickname) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			nickname,
			NOW,
			NOW);
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private long insertGame(long bggId, String name) {
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
			NOW,
			NOW);
		return jdbcTemplate.queryForObject("select id from games where bgg_id = ?", Long.class, bggId);
	}

	private long insertRoom(long gameId, long hostUserId, String title, String description, Instant startsAt) {
		jdbcTemplate.update(
			"""
				insert into rooms (
				    game_id, host_user_id, room_type, title, description, experience_level,
				    is_rulemaster_led, region, capacity, active_participant_count, start_at,
				    place, status, version, created_at, updated_at)
				values (?, ?, 'GAME_FOCUSED', ?, ?, 'ALL_LEVELS', false, '홍대', 6, 0, ?, '수동 장소', 'RECRUITING', 0, ?, ?)
				""",
			gameId,
			hostUserId,
			title,
			description,
			startsAt,
			NOW,
			NOW);
		return jdbcTemplate.queryForObject("select id from rooms where title = ?", Long.class, title);
	}

	private void insertParticipation(long roomId, long userId) {
		jdbcTemplate.update(
			"""
				insert into participations (room_id, user_id, status, joined_at, created_at, updated_at)
				values (?, ?, 'ACTIVE', ?, ?, ?)
				""",
			roomId,
			userId,
			NOW,
			NOW,
			NOW);
	}

	private int countSeedRooms(String type) {
		return countSeedRoomsMatching("and room.room_type = '" + type + "'");
	}

	private int countSeedRooms() {
		return countSeedRoomsMatching("");
	}

	private int countSeedRoomsMatching(String condition) {
		return jdbcTemplate.queryForObject(
			"""
				select count(*)
				from rooms room
				join users host on host.id = room.host_user_id
				where host.email = ? and room.title like ?
				""" + condition,
			Integer.class,
			SEED_HOST_EMAIL,
			SEED_TITLE_PREFIX);
	}

	private int countSeedRoomsWithGame(long bggId) {
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

	private int countSeedRoomsWithInternalDescription() {
		return countSeedRoomsMatching("and room.description like 'LOCAL-SEED:%'");
	}

	private int countSeedRoomsWithDescription(String description) {
		return jdbcTemplate.queryForObject(
			"""
				select count(*)
				from rooms room
				join users host on host.id = room.host_user_id
				where host.email = ? and room.title like ? and room.description = ?
				""",
			Integer.class,
			SEED_HOST_EMAIL,
			SEED_TITLE_PREFIX,
			description);
	}

	private int countReservedFallbackGames() {
		return jdbcTemplate.queryForObject(
			"select count(*) from games where bgg_id between ? and ?",
			Integer.class,
			LOCAL_GAME_BGG_ID_BASE - 30,
			LOCAL_GAME_BGG_ID_BASE - 1);
	}

	private int count(String sql) {
		return jdbcTemplate.queryForObject(sql, Integer.class);
	}
}
