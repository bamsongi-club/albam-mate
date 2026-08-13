package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.contract.GameRankingQuery;
import cloud.bamsongi.albammate.game.contract.GameRankingQuery.GameRoomCount;
import cloud.bamsongi.albammate.game.dto.GameRankingItem;
import cloud.bamsongi.albammate.game.dto.GameRankingResponse;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameRepository;

@ExtendWith(MockitoExtension.class)
class GameRankingQueryServiceTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-08-11T00:00:00Z");

	@Mock
	private GameRankingQuery gameRankingQuery;

	@Mock
	private GameRepository gameRepository;

	private GameRankingQueryService gameRankingQueryService;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
		gameRankingQueryService = new GameRankingQueryService(gameRankingQuery, gameRepository, clock);
	}

	@Test
	void 지난_7일_랭킹_조회는_7일_전_경계와_고정된_기준_시각을_계약에_전달한다() {
		Instant expectedFrom = FIXED_NOW.minus(Duration.ofDays(7));
		when(gameRankingQuery.findOverallRanking(10)).thenReturn(List.of());
		when(gameRankingQuery.findRankingByPeriod(expectedFrom, FIXED_NOW, 10)).thenReturn(List.of());

		gameRankingQueryService.findRankings();

		verify(gameRankingQuery).findOverallRanking(10);
		verify(gameRankingQuery).findRankingByPeriod(expectedFrom, FIXED_NOW, 10);
	}

	@Test
	void 집계_대상이_없으면_두_랭킹_모두_빈_배열이고_게임_저장소를_조회하지_않는다() {
		when(gameRankingQuery.findOverallRanking(10)).thenReturn(List.of());
		when(gameRankingQuery.findRankingByPeriod(any(), any(), eq(10))).thenReturn(List.of());

		GameRankingResponse response = gameRankingQueryService.findRankings();

		assertEquals(List.of(), response.overall());
		assertEquals(List.of(), response.pastWeek());
		verify(gameRepository, never()).findAllById(anyCollection());
	}

	@Test
	void 두_랭킹의_게임_ID를_합쳐_한_번만_게임을_조회하고_순위를_순서대로_부여한다() {
		Game gameA = gameWithId(1L, 1001L, "게임A", "urlA");
		Game gameB = gameWithId(2L, 1002L, "게임B", null);
		when(gameRankingQuery.findOverallRanking(10))
			.thenReturn(List.of(new GameRoomCount(1L, 5L), new GameRoomCount(2L, 5L)));
		when(gameRankingQuery.findRankingByPeriod(any(), any(), eq(10)))
			.thenReturn(List.of(new GameRoomCount(2L, 3L)));
		when(gameRepository.findAllById(Set.of(1L, 2L))).thenReturn(List.of(gameA, gameB));

		GameRankingResponse response = gameRankingQueryService.findRankings();

		assertEquals(
			List.of(
				new GameRankingItem(1, 1L, 1001L, "게임A", "Catan", null, "urlA", "게임 설명", 5L),
				new GameRankingItem(2, 2L, 1002L, "게임B", "Catan", null, null, "게임 설명", 5L)),
			response.overall());
		assertEquals(
			List.of(new GameRankingItem(1, 2L, 1002L, "게임B", "Catan", null, null, "게임 설명", 3L)),
			response.pastWeek());
		verify(gameRepository).findAllById(Set.of(1L, 2L));
	}

	@Test
	void 표시_정보가_없는_게임ID는_랭킹에서_제외하고_순위에_공백을_남기지_않는다() {
		Game gameA = gameWithId(1L, 1001L, "게임A", null);
		when(gameRankingQuery.findOverallRanking(10))
			.thenReturn(List.of(new GameRoomCount(1L, 5L), new GameRoomCount(2L, 1L)));
		when(gameRankingQuery.findRankingByPeriod(any(), any(), eq(10))).thenReturn(List.of());
		when(gameRepository.findAllById(Set.of(1L, 2L))).thenReturn(List.of(gameA));

		GameRankingResponse response = gameRankingQueryService.findRankings();

		assertEquals(
			List.of(new GameRankingItem(1, 1L, 1001L, "게임A", "Catan", null, null, "게임 설명", 5L)),
			response.overall());
	}

	private Game gameWithId(long id, long bggId, String name, String imageUrl) {
		Game game = GameFixture.valid(bggId, name);
		ReflectionTestUtils.setField(game, "id", id);
		ReflectionTestUtils.setField(game, "imageUrl", imageUrl);
		return game;
	}
}
