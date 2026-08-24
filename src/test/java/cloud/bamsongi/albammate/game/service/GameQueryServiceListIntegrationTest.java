package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.global.config.JpaConfig;
import cloud.bamsongi.albammate.global.config.TimeConfig;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.service.query.RoomUpcomingRoomCountQuery;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	GameQueryService.class,
	GameFilterValidator.class,
	RoomUpcomingRoomCountQuery.class,
	JpaConfig.class,
	TimeConfig.class,
	GameQueryServiceListIntegrationTest.FixedClockTestConfiguration.class
})
class GameQueryServiceListIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

	@Autowired
	private GameRepository gameRepository;

	@Autowired
	private GameQueryService gameQueryService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 예정_모임_수는_게임중심_미래_미종료방만_게임별로_집계한다() {
		long hostUserId = insertHostUser();
		Game gameWithTwoRooms = gameRepository.saveAndFlush(GameFixture.valid(1001L, "카탄"));
		Game gameWithOneRoom = gameRepository.saveAndFlush(GameFixture.valid(1002L, "아줄"));
		Game gameWithoutUpcomingRoom = gameRepository.saveAndFlush(GameFixture.valid(1003L, "윙스팬"));

		insertRoom(
			hostUserId,
			gameWithTwoRooms.getId(),
			RoomType.GAME_FOCUSED,
			NOW.plusSeconds(1),
			RoomStatus.RECRUITING);
		insertRoom(
			hostUserId,
			gameWithTwoRooms.getId(),
			RoomType.GAME_FOCUSED,
			NOW.plusSeconds(2),
			RoomStatus.CLOSED);
		insertRoom(
			hostUserId,
			gameWithTwoRooms.getId(),
			RoomType.GAME_FOCUSED,
			NOW.plusSeconds(3),
			RoomStatus.CANCELED);
		insertRoom(
			hostUserId,
			gameWithTwoRooms.getId(),
			RoomType.GAME_FOCUSED,
			NOW.plusSeconds(4),
			RoomStatus.FINISHED);
		insertRoom(
			hostUserId,
			gameWithTwoRooms.getId(),
			RoomType.GAME_FOCUSED,
			NOW.minusSeconds(1),
			RoomStatus.RECRUITING);
		insertRoom(
			hostUserId,
			gameWithTwoRooms.getId(),
			RoomType.GAME_FOCUSED,
			NOW,
			RoomStatus.RECRUITING);
		insertRoom(
			hostUserId,
			gameWithTwoRooms.getId(),
			RoomType.PERSON_FOCUSED,
			NOW.plusSeconds(5),
			RoomStatus.RECRUITING);
		insertRoom(
			hostUserId,
			gameWithOneRoom.getId(),
			RoomType.GAME_FOCUSED,
			NOW.plusSeconds(6),
			RoomStatus.RECRUITING);

		Slice<GameListItem> result = gameQueryService.findPage(listRequest(false, 10), null);

		Map<Long, Long> upcomingRoomCounts = result.getContent().stream()
			.collect(
				java.util.stream.Collectors.toMap(
					GameListItem::id, GameListItem::upcomingRoomCount));

		assertEquals(
			Map.of(
				gameWithTwoRooms.getId(), 2L,
				gameWithOneRoom.getId(), 1L,
				gameWithoutUpcomingRoom.getId(), 0L),
			upcomingRoomCounts);

		Slice<GameListItem> upcomingOnlyResult = gameQueryService.findPage(listRequest(true, 1), null);

		assertTrue(upcomingOnlyResult.hasNext());
		assertEquals(1L, upcomingOnlyResult.getContent().getFirst().upcomingRoomCount());
		assertEquals(gameWithOneRoom.getId(), upcomingOnlyResult.getContent().getFirst().id());
	}

	@Test
	void 최연소_참여자_나이는_입력값_이하의_minAge만_반환하고_NULL을_제외한다() {
		Game included = saveGameWithMinAge(2001L, "AgeTen", 10);
		saveGameWithMinAge(2002L, "AgeEleven", 11);
		saveGameWithMinAge(2003L, "AgeMissing", null);
		GameListRequest request = listRequest(false, 10);
		request.setYoungestPlayerAge(10);

		Slice<GameListItem> result = gameQueryService.findPage(request, null);

		assertEquals(List.of(included.getId()), result.getContent().stream().map(GameListItem::id).toList());
	}

	@Test
	void 최연소_참여자_나이를_생략하면_minAge_NULL을_포함해_기본_정렬과_페이지를_유지한다() {
		saveGameWithMinAge(2011L, "Alpha", null);
		Game beta = saveGameWithMinAge(2012L, "Beta", 12);
		GameListRequest request = listRequest(false, 1);
		request.setPage(1);

		Slice<GameListItem> result = gameQueryService.findPage(request, null);

		assertEquals(List.of(beta.getId()), result.getContent().stream().map(GameListItem::id).toList());
	}

	private Game saveGameWithMinAge(long bggId, String name, Integer minAge) {
		Game game = gameRepository.saveAndFlush(GameFixture.valid(bggId, name));
		ReflectionTestUtils.setField(game, "minAge", minAge);
		return gameRepository.saveAndFlush(game);
	}

	private GameListRequest listRequest(boolean upcomingOnly, int size) {
		GameListRequest request = new GameListRequest();
		request.setUpcomingOnly(upcomingOnly);
		request.setSize(size);
		return request;
	}

	private long insertHostUser() {
		String email = "game-list-host@example.com";
		jdbcTemplate.update(
			"""
				insert into users
				    (email, password_hash, nickname, created_at, updated_at)
				values
				    (?, 'test-hash', '게임 목록 테스트 호스트',
				     TIMESTAMP WITH TIME ZONE '2026-07-26T00:00:00Z',
				     TIMESTAMP WITH TIME ZONE '2026-07-26T00:00:00Z')
				""",
			email);

		return jdbcTemplate.queryForObject(
			"select id from users where email = ?", Long.class, email);
	}

	private void insertRoom(
		long hostUserId, Long gameId, RoomType roomType, Instant startAt, RoomStatus status) {
		jdbcTemplate.update(
			"""
				insert into rooms
				    (game_id, host_user_id, room_type, title, experience_level,
				     is_rulemaster_led, capacity, active_participant_count, start_at, place,
				     status, created_at, updated_at)
				values
				    (?, ?, ?, '게임 목록 집계 테스트 방', 'ALL_LEVELS', false, 2, 0,
				     CAST(? AS TIMESTAMP WITH TIME ZONE), '테스트 장소', ?,
				     TIMESTAMP WITH TIME ZONE '2026-07-26T00:00:00Z',
				     TIMESTAMP WITH TIME ZONE '2026-07-26T00:00:00Z')
				""",
			gameId,
			hostUserId,
			roomType.name(),
			startAt.toString(),
			status.name());
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockTestConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

	}
}
