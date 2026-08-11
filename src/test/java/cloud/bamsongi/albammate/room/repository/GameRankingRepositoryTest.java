package cloud.bamsongi.albammate.room.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.fixture.RoomFixture;

/**
 * 인기 게임 랭킹 집계 조회를 H2에서 검증한다.
 *
 * <p>공유 H2에는 커밋된 다른 테스트의 게임·방이 남아 있어 집계에 함께 잡히므로, 이 클래스가 만든 게임만 골라 순서와 집계 수를 단정한다.
 * 테이블 전체를 대상으로 한 정렬과 상한은 격리된 컨테이너를 쓰는 {@code GameRankingPostgresTest}가 확인한다.
 */
@SpringBootTest
@Transactional
class GameRankingRepositoryTest {

	private static final Instant BASE_TIME = Instant.parse("2026-08-11T00:00:00Z");
	/** 남은 행에 밀려도 이 클래스의 게임이 모두 결과에 들어오도록 상한을 넉넉히 둔다. */
	private static final int UNBOUNDED_LIMIT = 1_000;

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
				values ('game-ranking-repo-host@example.com', 'hash', '방장', ?, ?)
				""",
			BASE_TIME,
			BASE_TIME);
		hostUserId = jdbcTemplate.queryForObject(
			"select id from users where email = 'game-ranking-repo-host@example.com'",
			Long.class);
	}

	@Test
	void 전체_집계는_GAME_FOCUSED이고_CANCELED가_아닌_방만_게임별로_센다() {
		Long gameA = insertGame(96001L, "게임A");
		Long gameB = insertGame(96002L, "게임B");
		saveRoom(RoomType.GAME_FOCUSED, gameA, BASE_TIME.plusSeconds(60));
		Room closed = saveRoom(RoomType.GAME_FOCUSED, gameA, BASE_TIME.plusSeconds(120));
		Room finished = saveRoom(RoomType.GAME_FOCUSED, gameA, BASE_TIME.plusSeconds(180));
		Room canceled = saveRoom(RoomType.GAME_FOCUSED, gameA, BASE_TIME.plusSeconds(240));
		saveRoom(RoomType.PERSON_FOCUSED, gameB, BASE_TIME.plusSeconds(300));
		updateStatus(closed.getId(), "CLOSED");
		updateStatus(finished.getId(), "FINISHED");
		updateStatus(canceled.getId(), "CANCELED");

		List<RoomRepository.GameRankingCount> result = findOverall(UNBOUNDED_LIMIT);

		assertEquals(List.of(entry(gameA, 3L)), ownEntries(result, gameA, gameB));
	}

	@Test
	void 기간_집계는_시작_시각이_구간_안인_방만_게임별로_센다() {
		Long gameA = insertGame(96011L, "게임A");
		Instant from = BASE_TIME;
		Instant to = BASE_TIME.plus(Duration.ofDays(7));
		saveRoom(RoomType.GAME_FOCUSED, gameA, from);
		saveRoom(RoomType.GAME_FOCUSED, gameA, to.minusSeconds(1));
		saveRoom(RoomType.GAME_FOCUSED, gameA, to);
		saveRoom(RoomType.GAME_FOCUSED, gameA, from.minusSeconds(1));

		List<RoomRepository.GameRankingCount> result = roomRepository.findGameRankingCounts(
			RoomType.GAME_FOCUSED, List.of(RoomStatus.CANCELED), true, from, to,
			PageRequest.of(0, UNBOUNDED_LIMIT));

		assertEquals(List.of(entry(gameA, 2L)), ownEntries(result, gameA));
	}

	@Test
	void 집계_모임_수_내림차순_게임ID_오름차순으로_정렬한다() {
		Long gameLow = insertGame(96021L, "게임Low");
		Long gameHigh = insertGame(96022L, "게임High");
		Long gameTop = insertGame(96023L, "게임Top");
		saveRoom(RoomType.GAME_FOCUSED, gameTop, BASE_TIME.plusSeconds(10));
		saveRoom(RoomType.GAME_FOCUSED, gameTop, BASE_TIME.plusSeconds(20));
		saveRoom(RoomType.GAME_FOCUSED, gameTop, BASE_TIME.plusSeconds(30));
		saveRoom(RoomType.GAME_FOCUSED, gameHigh, BASE_TIME.plusSeconds(40));
		saveRoom(RoomType.GAME_FOCUSED, gameLow, BASE_TIME.plusSeconds(50));

		List<RoomRepository.GameRankingCount> result = findOverall(UNBOUNDED_LIMIT);

		// 3개인 게임이 먼저 오고, 1개로 동률인 두 게임은 게임 ID 오름차순으로 이어진다.
		assertEquals(
			List.of(entry(gameTop, 3L), entry(gameLow, 1L), entry(gameHigh, 1L)),
			ownEntries(result, gameLow, gameHigh, gameTop));
	}

	@Test
	void 상위_limit_개까지만_반환한다() {
		Long gameA = insertGame(96031L, "게임A");
		Long gameB = insertGame(96032L, "게임B");
		Long gameC = insertGame(96033L, "게임C");
		saveRoom(RoomType.GAME_FOCUSED, gameA, BASE_TIME.plusSeconds(10));
		saveRoom(RoomType.GAME_FOCUSED, gameB, BASE_TIME.plusSeconds(20));
		saveRoom(RoomType.GAME_FOCUSED, gameC, BASE_TIME.plusSeconds(30));

		// 이 클래스만으로도 집계 대상 게임이 셋이라 상한이 실제로 결과를 자른다.
		assertEquals(2, findOverall(2).size());
		assertEquals(1, findOverall(1).size());
	}

	@Test
	void 집계_대상_방이_없으면_결과에_들어가지_않는다() {
		Long gameA = insertGame(96041L, "게임A");
		Room canceled = saveRoom(RoomType.GAME_FOCUSED, gameA, BASE_TIME.plusSeconds(10));
		updateStatus(canceled.getId(), "CANCELED");
		saveRoom(RoomType.PERSON_FOCUSED, gameA, BASE_TIME.plusSeconds(20));

		List<RoomRepository.GameRankingCount> result = findOverall(UNBOUNDED_LIMIT);

		assertEquals(List.of(), ownEntries(result, gameA));
	}

	private List<RoomRepository.GameRankingCount> findOverall(int limit) {
		return roomRepository.findGameRankingCounts(
			RoomType.GAME_FOCUSED, List.of(RoomStatus.CANCELED), false, Instant.EPOCH, Instant.EPOCH,
			PageRequest.of(0, limit));
	}

	/** 공유 H2에 남은 다른 테스트의 게임을 빼고 이 테스트가 만든 게임의 순서와 집계 수만 남긴다. */
	private List<List<Long>> ownEntries(List<RoomRepository.GameRankingCount> result, Long... ownGameIds) {
		List<Long> own = List.of(ownGameIds);
		return result.stream()
			.filter(count -> own.contains(count.getGameId()))
			.map(count -> entry(count.getGameId(), count.getRoomCount()))
			.toList();
	}

	private List<Long> entry(Long gameId, long roomCount) {
		return List.of(gameId, roomCount);
	}

	private Long insertGame(long bggId, String name) {
		jdbcTemplate.update(
			"""
				insert into games (
				    bgg_id, name, english_name, supported_player_count, tag,
				    estimated_play_time, description, detail_description, created_at, updated_at)
				values (?, ?, 'Test', '3~4명', '전략', '60분', '설명', '상세 설명', ?, ?)
				""",
			bggId,
			name,
			BASE_TIME,
			BASE_TIME);
		return jdbcTemplate.queryForObject("select id from games where bgg_id = ?", Long.class, bggId);
	}

	private Room saveRoom(RoomType roomType, Long gameId, Instant startAt) {
		return roomRepository.saveAndFlush(RoomFixture.create(hostUserId, roomType, gameId, startAt));
	}

	private void updateStatus(Long roomId, String status) {
		jdbcTemplate.update("update rooms set status = ? where id = ?", status, roomId);
	}
}
