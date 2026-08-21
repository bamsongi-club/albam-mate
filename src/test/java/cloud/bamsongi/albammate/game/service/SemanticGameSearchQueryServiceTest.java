package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearch;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchMode;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchQuery;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchResult;
import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchRequest;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchResponse;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;

class SemanticGameSearchQueryServiceTest {

	private final SemanticGameSearch semanticGameSearch = mock(SemanticGameSearch.class);
	private final GameRepository gameRepository = mock(GameRepository.class);
	private final UpcomingRoomCountQuery upcomingRoomCountQuery = mock(UpcomingRoomCountQuery.class);
	private final UserPlayedGameRepository userPlayedGameRepository = mock(UserPlayedGameRepository.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC);
	private final SemanticGameSearchQueryService service = new SemanticGameSearchQueryService(
		semanticGameSearch, gameRepository, upcomingRoomCountQuery, userPlayedGameRepository, clock);

	@Test
	void T2_비로그인_playedFilter_요청은_core_호출없이_UNAUTHENTICATED다() {
		SemanticGameSearchRequest request = request("트릭테이킹 협력 게임");
		request.setPlayedFilter(List.of(PlayedFilter.PLAYED_ONLY));

		assertThrows(UnauthenticatedException.class, () -> service.search(request, null));

		verifyNoInteractions(semanticGameSearch);
		verifyNoInteractions(gameRepository);
	}

	@Test
	void T3_relevance_순서를_보존해_GameListItem으로_재조립하고_점수와_원문을_노출하지_않는다() {
		Game first = entityGame(10L, 1010L, "첫번째 게임");
		Game second = entityGame(20L, 1020L, "두번째 게임");
		SemanticGameSearchRequest request = request("초보자와 즐기기 좋은 협력 게임");
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class))).thenReturn(
			new SemanticGameSearchResult(
				SemanticGameSearchMode.SEMANTIC,
				List.of(new GameSummary(first.getId(), first.getBggId(), first.getName()),
					new GameSummary(second.getId(), second.getBggId(), second.getName())),
				true));
		when(gameRepository.findAllById(anyList())).thenReturn(List.of(second, first));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(anyList(), any())).thenReturn(Map.of());

		SemanticGameSearchResponse response = service.search(request, null);

		assertEquals(SemanticGameSearchMode.SEMANTIC, response.searchMode());
		assertEquals(List.of(first.getId(), second.getId()),
			response.content().stream().map(item -> item.id()).toList());
		assertTrue(response.hasNext());
		List<String> responseFields = List.of(SemanticGameSearchResponse.class.getRecordComponents()).stream()
			.map(component -> component.getName()).toList();
		org.junit.jupiter.api.Assertions
			.assertTrue(responseFields.stream().noneMatch(field -> field.contains("score")
				|| field.contains("vector") || field.contains("query") || field.contains("relevance")));
	}

	@Test
	void T3_존재하지_않는_game_id는_결과에서_제외한다() {
		Game onlyExisting = entityGame(30L, 1030L, "존재하는 게임");
		SemanticGameSearchRequest request = request("일꾼 놓기 게임");
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class))).thenReturn(
			new SemanticGameSearchResult(
				SemanticGameSearchMode.SEMANTIC,
				List.of(new GameSummary(999L, 1999L, "삭제된 게임"),
					new GameSummary(onlyExisting.getId(), onlyExisting.getBggId(), onlyExisting.getName())),
				false));
		when(gameRepository.findAllById(anyList())).thenReturn(List.of(onlyExisting));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(anyList(), any())).thenReturn(Map.of());

		SemanticGameSearchResponse response = service.search(request, null);

		assertEquals(List.of(onlyExisting.getId()), response.content().stream().map(item -> item.id()).toList());
	}

	@Test
	void T4_hard_filter를_만족하는_후보가_없으면_빈_페이지를_반환한다() {
		SemanticGameSearchRequest request = request("존재하지 않는 조합의 게임");
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class)))
			.thenReturn(new SemanticGameSearchResult(SemanticGameSearchMode.SEMANTIC, List.of(), false));

		SemanticGameSearchResponse response = service.search(request, null);

		assertEquals(List.of(), response.content());
		assertFalse(response.hasNext());
		verify(gameRepository, never()).findAllById(anyList());
	}

	@Test
	void T5_core_결과가_UNAVAILABLE이면_503_SEARCH_UNAVAILABLE로_던진다() {
		SemanticGameSearchRequest request = request("협력 게임");
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class)))
			.thenReturn(new SemanticGameSearchResult(SemanticGameSearchMode.UNAVAILABLE, List.of(), false));

		BusinessException exception = assertThrows(BusinessException.class, () -> service.search(request, null));

		assertEquals(ErrorCode.SEARCH_UNAVAILABLE, exception.getErrorCode());
		verify(gameRepository, never()).findAllById(anyList());
	}

	@Test
	void T5_core_결과가_LEXICAL_FALLBACK이면_명시적_fallback_상태의_성공_응답이다() {
		Game fallbackGame = entityGame(40L, 1040L, "폴백 게임");
		SemanticGameSearchRequest request = request("협력 게임");
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class))).thenReturn(
			new SemanticGameSearchResult(
				SemanticGameSearchMode.LEXICAL_FALLBACK,
				List.of(new GameSummary(fallbackGame.getId(), fallbackGame.getBggId(), fallbackGame.getName())),
				false));
		when(gameRepository.findAllById(anyList())).thenReturn(List.of(fallbackGame));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(anyList(), any())).thenReturn(Map.of());

		SemanticGameSearchResponse response = service.search(request, null);

		assertEquals(SemanticGameSearchMode.LEXICAL_FALLBACK, response.searchMode());
		assertEquals(List.of(fallbackGame.getId()), response.content().stream().map(item -> item.id()).toList());
	}

	private SemanticGameSearchRequest request(String query) {
		SemanticGameSearchRequest request = new SemanticGameSearchRequest();
		request.setQuery(query);
		return request;
	}

	private Game entityGame(long id, long bggId, String name) {
		Game game = GameFixture.valid(bggId, name);
		ReflectionTestUtils.setField(game, "id", id);
		return game;
	}
}
