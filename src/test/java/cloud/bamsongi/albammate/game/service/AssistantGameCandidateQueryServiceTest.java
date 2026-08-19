package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.contract.AssistantGameCandidateQuery;
import cloud.bamsongi.albammate.game.contract.GameRankingQuery;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameRepository;

class AssistantGameCandidateQueryServiceTest {

	@Test
	void T5_후보_집합에만_RANK_01을_적용하고_집계없는_게임은_0건과_ID_오름차순으로_보완한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		GameRankingQuery gameRankingQuery = org.mockito.Mockito.mock(GameRankingQuery.class);
		Game first = game(1001L, 11L, "첫 후보");
		Game second = game(1002L, 12L, "둘째 후보");
		Game third = game(1003L, 13L, "셋째 후보");
		when(gameRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
			.thenReturn(List.of(third, first, second));
		when(gameRankingQuery.findOverallRankingForGameIds(List.of(13L, 11L, 12L)))
			.thenReturn(List.of(
				new GameRankingQuery.GameRoomCount(12L, 2),
				new GameRankingQuery.GameRoomCount(11L, 2)));

		var result = new AssistantGameCandidateQueryService(gameRepository, gameRankingQuery)
			.findCandidates(new AssistantGameCandidateQuery.Criteria(List.of("STRATEGY")));

		assertEquals(List.of(11L, 12L, 13L), result.stream().map(candidate -> candidate.id()).toList());
	}

	private Game game(long bggId, long id, String name) {
		Game game = GameFixture.valid(bggId, name);
		ReflectionTestUtils.setField(game, "id", id);
		return game;
	}
}
