package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.game.contract.GamePlayerRange;
import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.repository.GameRepository;

@ExtendWith(MockitoExtension.class)
class GameSummaryQueryServiceTest {

	@Mock
	private GameRepository gameRepository;

	@InjectMocks
	private GameSummaryQueryService gameSummaryQueryService;

	@Test
	void GameQuery_공개_계약을_구현한다() {
		GameQuery gameQuery = gameSummaryQueryService;

		assertEquals(GameSummaryQueryService.class, gameQuery.getClass());
	}

	@Test
	void 요약_조회는_전체_Game이_아닌_projection을_위임한다() {
		Long gameId = 1L;
		GameSummary expected = new GameSummary(gameId, 1001L, "카탄");
		when(gameRepository.findSummaryById(gameId)).thenReturn(Optional.of(expected));

		assertEquals(Optional.of(expected), gameSummaryQueryService.findSummaryById(gameId));

		verify(gameRepository).findSummaryById(gameId);
		verify(gameRepository, never()).findById(gameId);
	}

	@Test
	void 게임_지원_인원_범위는_매칭_전용_projection으로_조회한다() {
		Long gameId = 1L;
		GamePlayerRange expected = new GamePlayerRange(gameId, 2, 4);
		when(gameRepository.findPlayerRangeById(gameId)).thenReturn(Optional.of(expected));

		assertEquals(Optional.of(expected), gameSummaryQueryService.findPlayerRangeById(gameId));

		verify(gameRepository).findPlayerRangeById(gameId);
		verify(gameRepository, never()).findById(gameId);
	}

	@Test
	void 여러_요약_조회는_하나의_projection_조회로_위임한다() {
		List<Long> gameIds = List.of(1L, 2L);
		List<GameSummary> summaries = List.of(new GameSummary(1L, 1001L, "카탄"), new GameSummary(2L, 1002L, "아줄"));
		when(gameRepository.findSummariesByIds(gameIds)).thenReturn(summaries);

		assertEquals(
			Map.of(1L, summaries.getFirst(), 2L, summaries.getLast()),
			gameSummaryQueryService.findSummariesByIds(gameIds));

		verify(gameRepository).findSummariesByIds(gameIds);
		verify(gameRepository, never()).findAllById(gameIds);
	}

	@Test
	void 빈_ID_컬렉션은_저장소를_조회하지_않는다() {
		assertEquals(Map.of(), gameSummaryQueryService.findSummariesByIds(List.of()));

		verify(gameRepository, never()).findSummariesByIds(List.of());
	}
}
