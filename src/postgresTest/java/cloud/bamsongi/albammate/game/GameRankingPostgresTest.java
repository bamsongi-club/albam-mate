package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/**
 * H2에서 검증한 인기 게임 랭킹 집계·정렬·상한이 PostgreSQL에서도 재현되는지 확인하고, 대표 분포 쿼리의 실행 계획을 확인한다.
 *
 * <p>측정된 병목이 없으므로 인덱스나 Flyway 마이그레이션은 추가하지 않는다.
 */
@Testcontainers
@SpringBootTest
class GameRankingPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant BASE_TIME = Instant.parse("2099-01-01T00:00:00Z");
	private static final OffsetDateTime BASE_TIME_UTC = BASE_TIME.atOffset(ZoneOffset.UTC);

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("game_ranking_test");

	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long hostUserId;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update(
			"""
				insert into users (email, password_hash, nickname, created_at, updated_at)
				values ('game-ranking-postgres-host@example.com', 'hash', '방장', ?, ?)
				""",
			BASE_TIME_UTC,
			BASE_TIME_UTC);
		hostUserId = jdbcTemplate.queryForObject(
			"select id from users where email = 'game-ranking-postgres-host@example.com'",
			Long.class);
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table rooms, games, users restart identity cascade");
	}

	@Test
	void 대표_분포에서_전체_집계가_정렬과_상위_10개_상한으로_재현된다() {
		List<Long> gameIds = insertGames(8_100_000L, 12);
		for (int index = 0; index < gameIds.size(); index++) {
			int roomCountForGame = index + 1;
			for (int roomIndex = 0; roomIndex < roomCountForGame; roomIndex++) {
				saveRoom(RoomType.GAME_FOCUSED, gameIds.get(index), BASE_TIME.plusSeconds(index * 100L + roomIndex));
			}
		}
		Long topGameId = gameIds.get(11);
		Room canceled = saveRoom(RoomType.GAME_FOCUSED, topGameId, BASE_TIME.plusSeconds(9_000));
		jdbcTemplate.update("update rooms set status = 'CANCELED' where id = ?", canceled.getId());
		saveRoom(RoomType.PERSON_FOCUSED, topGameId, BASE_TIME.plusSeconds(9_001));

		List<RoomRepository.GameRankingCount> result = roomRepository.findGameRankingCounts(
			RoomType.GAME_FOCUSED, List.of(RoomStatus.CANCELED), false, Instant.EPOCH, Instant.EPOCH,
			PageRequest.of(0, 10));

		assertEquals(10, result.size());
		assertEquals(topGameId, result.get(0).getGameId());
		assertEquals(12L, result.get(0).getRoomCount());
		for (int index = 0; index + 1 < result.size(); index++) {
			long currentCount = result.get(index).getRoomCount();
			long nextCount = result.get(index + 1).getRoomCount();
			assertTrue(
				currentCount > nextCount
					|| (currentCount == nextCount && result.get(index).getGameId() < result.get(index + 1).getGameId()),
				"내림차순(동률이면 게임ID 오름차순)이 아님: " + result);
		}
	}

	@Test
	void 기간_조건은_시작_구간_안의_방만_집계해_재현된다() {
		Long insideGame = insertGames(8_200_001L, 1).get(0);
		Long outsideGame = insertGames(8_200_002L, 1).get(0);
		Instant from = BASE_TIME;
		Instant to = BASE_TIME.plus(Duration.ofDays(7));
		saveRoom(RoomType.GAME_FOCUSED, insideGame, from);
		saveRoom(RoomType.GAME_FOCUSED, insideGame, to.minusSeconds(1));
		saveRoom(RoomType.GAME_FOCUSED, outsideGame, to);
		saveRoom(RoomType.GAME_FOCUSED, outsideGame, from.minusSeconds(1));

		List<RoomRepository.GameRankingCount> result = roomRepository.findGameRankingCounts(
			RoomType.GAME_FOCUSED, List.of(RoomStatus.CANCELED), true, from, to, PageRequest.of(0, 10));

		assertEquals(1, result.size());
		assertEquals(insideGame, result.get(0).getGameId());
		assertEquals(2L, result.get(0).getRoomCount());
	}

	@Test
	void 대표_쿼리의_실행_계획을_확인한다() {
		List<Long> gameIds = insertGames(8_300_000L, 12);
		for (int index = 0; index < gameIds.size(); index++) {
			int roomCountForGame = index + 1;
			for (int roomIndex = 0; roomIndex < roomCountForGame; roomIndex++) {
				saveRoom(RoomType.GAME_FOCUSED, gameIds.get(index), BASE_TIME.plusSeconds(index * 100L + roomIndex));
			}
		}
		jdbcTemplate.execute("analyze rooms");

		String plan = jdbcTemplate.queryForObject(
			"""
				explain (analyze, buffers, format json)
				select game_id, count(id)
				from rooms
				where room_type = 'GAME_FOCUSED' and status <> 'CANCELED'
				group by game_id
				order by count(id) desc, game_id asc
				limit 10
				""",
			String.class);

		assertTrue(plan.contains("\"Node Type\": \"Limit\""), plan);
		assertEquals(10, planNumber(plan, "Actual Rows").intValue(), plan);
		assertTrue(planNumber(plan, "Planning Time") > 0, plan);
		assertTrue(planNumber(plan, "Execution Time") > 0, plan);
	}

	/** 대표 분포(12게임·78방)의 EXPLAIN ANALYZE 결과에서 최상위 Limit 노드의 수치를 읽는다. */
	private Double planNumber(String plan, String field) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*([0-9.]+)")
			.matcher(plan);
		assertTrue(matcher.find(), field + " 필드를 실행 계획에서 찾지 못함: " + plan);
		return Double.parseDouble(matcher.group(1));
	}

	private List<Long> insertGames(long baseBggId, int count) {
		List<Long> ids = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			long bggId = baseBggId + index;
			jdbcTemplate.update(
				"""
					insert into games (
					    bgg_id, name, english_name, supported_player_count, tag,
					    estimated_play_time, description, detail_description, created_at, updated_at)
					values (?, ?, 'Test', '3~4명', '전략', '60분', '설명', '상세 설명', ?, ?)
					""",
				bggId,
				"랭킹게임" + bggId,
				BASE_TIME_UTC,
				BASE_TIME_UTC);
			ids.add(jdbcTemplate.queryForObject("select id from games where bgg_id = ?", Long.class, bggId));
		}
		return ids;
	}

	private Room saveRoom(RoomType roomType, Long gameId, Instant startAt) {
		return roomRepository.saveAndFlush(
			Room.create(
				hostUserId, roomType, "테스트 방", null, gameId, ExperienceLevel.ALL_LEVELS, false, startAt, "테스트 장소", 4));
	}
}
