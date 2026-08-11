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

@SpringBootTest
@Transactional
class GameRankingRepositoryTest {

	private static final Instant BASE_TIME = Instant.parse("2026-08-11T00:00:00Z");

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
		Long gameA = insertGame(9001L, "게임A");
		Long gameB = insertGame(9002L, "게임B");
		saveRoom(RoomType.GAME_FOCUSED, gameA, BASE_TIME.plusSeconds(60));
		Room closed = saveRoom(RoomType.GAME_FOCUSED, gameA, BASE_TIME.plusSeconds(120));
		Room finished = saveRoom(RoomType.GAME_FOCUSED, gameA, BASE_TIME.plusSeconds(180));
		Room canceled = saveRoom(RoomType.GAME_FOCUSED, gameA, BASE_TIME.plusSeconds(240));
		saveRoom(RoomType.PERSON_FOCUSED, gameB, BASE_TIME.plusSeconds(300));
		updateStatus(closed.getId(), "CLOSED");
		updateStatus(finished.getId(), "FINISHED");
		updateStatus(canceled.getId(), "CANCELED");

		List<RoomRepository.GameRankingCount> result = roomRepository.findGameRankingCounts(
			RoomType.GAME_FOCUSED, List.of(RoomStatus.CANCELED), false, Instant.EPOCH, Instant.EPOCH,
			PageRequest.of(0, 10));

		assertEquals(1, result.size());
		assertEquals(gameA, result.get(0).getGameId());
		assertEquals(3L, result.get(0).getRoomCount());
	}

	@Test
	void 기간_집계는_시작_시각이_구간_안인_방만_게임별로_센다() {
		Long gameA = insertGame(9011L, "게임A");
		Instant from = BASE_TIME;
		Instant to = BASE_TIME.plus(Duration.ofDays(7));
		saveRoom(RoomType.GAME_FOCUSED, gameA, from);
		saveRoom(RoomType.GAME_FOCUSED, gameA, to.minusSeconds(1));
		saveRoom(RoomType.GAME_FOCUSED, gameA, to);
		saveRoom(RoomType.GAME_FOCUSED, gameA, from.minusSeconds(1));

		List<RoomRepository.GameRankingCount> result = roomRepository.findGameRankingCounts(
			RoomType.GAME_FOCUSED, List.of(RoomStatus.CANCELED), true, from, to, PageRequest.of(0, 10));

		assertEquals(1, result.size());
		assertEquals(gameA, result.get(0).getGameId());
		assertEquals(2L, result.get(0).getRoomCount());
	}

	@Test
	void 집계_모임_수_내림차순_게임ID_오름차순으로_정렬한다() {
		Long gameLow = insertGame(9021L, "게임Low");
		Long gameHigh = insertGame(9022L, "게임High");
		Long gameTop = insertGame(9023L, "게임Top");
		saveRoom(RoomType.GAME_FOCUSED, gameTop, BASE_TIME.plusSeconds(10));
		saveRoom(RoomType.GAME_FOCUSED, gameTop, BASE_TIME.plusSeconds(20));
		saveRoom(RoomType.GAME_FOCUSED, gameTop, BASE_TIME.plusSeconds(30));
		saveRoom(RoomType.GAME_FOCUSED, gameHigh, BASE_TIME.plusSeconds(40));
		saveRoom(RoomType.GAME_FOCUSED, gameLow, BASE_TIME.plusSeconds(50));

		List<RoomRepository.GameRankingCount> result = roomRepository.findGameRankingCounts(
			RoomType.GAME_FOCUSED, List.of(RoomStatus.CANCELED), false, Instant.EPOCH, Instant.EPOCH,
			PageRequest.of(0, 10));

		assertEquals(
			List.of(gameTop, gameLow, gameHigh),
			result.stream().map(RoomRepository.GameRankingCount::getGameId).toList());
		assertEquals(List.of(3L, 1L, 1L), result.stream().map(RoomRepository.GameRankingCount::getRoomCount).toList());
	}

	@Test
	void 상위_limit_개까지만_반환한다() {
		Long gameA = insertGame(9031L, "게임A");
		Long gameB = insertGame(9032L, "게임B");
		Long gameC = insertGame(9033L, "게임C");
		saveRoom(RoomType.GAME_FOCUSED, gameA, BASE_TIME.plusSeconds(10));
		saveRoom(RoomType.GAME_FOCUSED, gameB, BASE_TIME.plusSeconds(20));
		saveRoom(RoomType.GAME_FOCUSED, gameC, BASE_TIME.plusSeconds(30));

		List<RoomRepository.GameRankingCount> result = roomRepository.findGameRankingCounts(
			RoomType.GAME_FOCUSED, List.of(RoomStatus.CANCELED), false, Instant.EPOCH, Instant.EPOCH,
			PageRequest.of(0, 2));

		assertEquals(2, result.size());
		assertEquals(
			List.of(gameA, gameB), result.stream().map(RoomRepository.GameRankingCount::getGameId).toList());
	}

	@Test
	void 집계_대상_방이_없으면_빈_리스트를_반환한다() {
		Long gameA = insertGame(9041L, "게임A");
		Room canceled = saveRoom(RoomType.GAME_FOCUSED, gameA, BASE_TIME.plusSeconds(10));
		updateStatus(canceled.getId(), "CANCELED");
		saveRoom(RoomType.PERSON_FOCUSED, gameA, BASE_TIME.plusSeconds(20));

		List<RoomRepository.GameRankingCount> result = roomRepository.findGameRankingCounts(
			RoomType.GAME_FOCUSED, List.of(RoomStatus.CANCELED), false, Instant.EPOCH, Instant.EPOCH,
			PageRequest.of(0, 10));

		assertEquals(List.of(), result);
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
