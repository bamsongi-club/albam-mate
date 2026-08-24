package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.game.contract.AssistantGameCandidateQuery;
import cloud.bamsongi.albammate.game.contract.GameRankingQuery;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearch;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchMode;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchQuery;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchResult;
import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchRequest;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

class GameFilterValidationConsistencyTest {

	@Test
	void T3_목록_semantic_assistant는_같은_잘못된_category를_같은_오류로_거절한다() {
		GameRepository gameRepository = mock(GameRepository.class);
		GameCategoryRepository categoryRepository = mock(GameCategoryRepository.class);
		GameMechanismRepository mechanismRepository = mock(GameMechanismRepository.class);
		GameThemeRepository themeRepository = mock(GameThemeRepository.class);
		SemanticGameSearch semanticGameSearch = mock(SemanticGameSearch.class);
		when(categoryRepository.countByCodeIn(List.of("UNKNOWN_CATEGORY"))).thenReturn(0L);
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class))).thenReturn(
			new SemanticGameSearchResult(SemanticGameSearchMode.SEMANTIC, List.of(), false));
		GameFilterValidator gameFilterValidator = new GameFilterValidator(
			mechanismRepository, categoryRepository, themeRepository);

		GameQueryService gameQueryService = new GameQueryService(
			gameRepository,
			Clock.systemUTC(),
			mock(UpcomingRoomCountQuery.class),
			mock(UserPlayedGameRepository.class),
			gameFilterValidator,
			mock(JdbcTemplate.class));
		SemanticGameSearchQueryService semanticGameSearchQueryService = new SemanticGameSearchQueryService(
			semanticGameSearch,
			gameRepository,
			mock(UpcomingRoomCountQuery.class),
			mock(UserPlayedGameRepository.class),
			Clock.systemUTC(),
			gameFilterValidator);
		AssistantGameCandidateQueryService assistantGameCandidateQueryService = new AssistantGameCandidateQueryService(
			gameRepository, mock(GameRankingQuery.class), gameFilterValidator);
		GameListRequest listRequest = new GameListRequest();
		listRequest.setCategory(List.of("UNKNOWN_CATEGORY"));
		SemanticGameSearchRequest semanticRequest = new SemanticGameSearchRequest();
		semanticRequest.setQuery("협력 게임");
		semanticRequest.setCategory(List.of("UNKNOWN_CATEGORY"));

		assertValidationError(() -> gameQueryService.findPage(listRequest, null));
		assertValidationError(() -> assistantGameCandidateQueryService.validateCriteria(
			new AssistantGameCandidateQuery.Criteria(List.of("UNKNOWN_CATEGORY"))));
		assertValidationError(() -> semanticGameSearchQueryService.search(semanticRequest, null));

		org.mockito.Mockito.verify(categoryRepository, org.mockito.Mockito.times(3))
			.countByCodeIn(List.of("UNKNOWN_CATEGORY"));
	}

	private void assertValidationError(org.junit.jupiter.api.function.Executable executable) {
		BusinessException exception = assertThrows(BusinessException.class, executable);
		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
	}
}
