package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import cloud.bamsongi.albammate.game.contract.AssistantGameCandidateQuery;
import cloud.bamsongi.albammate.game.contract.GameRankingQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;

class AssistantGameCandidateQueryServiceTest {

	@Test
	void T5_후보_집합에만_RANK_01을_적용하고_집계없는_게임은_0건과_ID_오름차순으로_보완한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		GameRankingQuery gameRankingQuery = org.mockito.Mockito.mock(GameRankingQuery.class);
		GameSummary first = summary(11L, 1001L, "첫 후보");
		GameSummary second = summary(12L, 1002L, "둘째 후보");
		GameSummary third = summary(13L, 1003L, "셋째 후보");
		when(gameRepository.findCandidateSummaries(any(), any()))
			.thenReturn(new SliceImpl<>(List.of(third, first, second), PageRequest.of(0, 500), false));
		when(gameRankingQuery.findOverallRankingForGameIds(List.of(13L, 11L, 12L)))
			.thenReturn(List.of(
				new GameRankingQuery.GameRoomCount(12L, 2),
				new GameRankingQuery.GameRoomCount(11L, 2)));

		var result = service(gameRepository, gameRankingQuery)
			.findCandidates(new AssistantGameCandidateQuery.Criteria(List.of("STRATEGY")));

		assertEquals(List.of(11L, 12L, 13L), result.stream().map(candidate -> candidate.id()).toList());
	}

	@Test
	void T5_플레이시간과_특정_게임_조건의_빈_후보는_랭킹_조회없이_종료한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		GameRankingQuery gameRankingQuery = org.mockito.Mockito.mock(GameRankingQuery.class);
		when(gameRepository.findCandidateSummaries(any(), any()))
			.thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 500), false));

		var result = service(gameRepository, gameRankingQuery)
			.findCandidates(new AssistantGameCandidateQuery.Criteria(
				List.of("STRATEGY"), List.of(), List.of(), null, "UP_TO_10", 1001L, null));

		assertEquals(List.of(), result);
		verifyNoInteractions(gameRankingQuery);
	}

	private AssistantGameCandidateQueryService service(
		GameRepository gameRepository,
		GameRankingQuery gameRankingQuery) {
		return new AssistantGameCandidateQueryService(
			gameRepository,
			gameRankingQuery,
			org.mockito.Mockito.mock(GameCategoryRepository.class),
			org.mockito.Mockito.mock(GameMechanismRepository.class),
			org.mockito.Mockito.mock(GameThemeRepository.class));
	}

	private GameSummary summary(long id, long bggId, String name) {
		return new GameSummary(id, bggId, name);
	}
}
