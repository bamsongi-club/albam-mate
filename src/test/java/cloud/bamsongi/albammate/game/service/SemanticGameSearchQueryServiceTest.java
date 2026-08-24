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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearch;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchMode;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchQuery;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchResult;
import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.MechanismMatch;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchRequest;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchResponse;
import cloud.bamsongi.albammate.game.dto.ThemeMatch;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;

class SemanticGameSearchQueryServiceTest {

	private final SemanticGameSearch semanticGameSearch = mock(SemanticGameSearch.class);
	private final GameRepository gameRepository = mock(GameRepository.class);
	private final GameMechanismRepository gameMechanismRepository = mock(GameMechanismRepository.class);
	private final GameCategoryRepository gameCategoryRepository = mock(GameCategoryRepository.class);
	private final GameThemeRepository gameThemeRepository = mock(GameThemeRepository.class);
	private final GameFilterValidator gameFilterValidator = new GameFilterValidator(
		gameMechanismRepository, gameCategoryRepository, gameThemeRepository);
	private final UpcomingRoomCountQuery upcomingRoomCountQuery = mock(UpcomingRoomCountQuery.class);
	private final UserPlayedGameRepository userPlayedGameRepository = mock(UserPlayedGameRepository.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC);
	private final SemanticGameSearchQueryService service = new SemanticGameSearchQueryService(
		semanticGameSearch,
		gameRepository,
		upcomingRoomCountQuery,
		userPlayedGameRepository,
		clock,
		gameFilterValidator);

	@Test
	void T1_존재하지않거나_비공개인_metadata_code는_candidate와_게임조회_전에_검증오류다() {
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class))).thenReturn(emptySemanticResult());
		when(gameMechanismRepository.countByCodeInAndIsPublicTrue(List.of("PRIVATE"))).thenReturn(0L);
		when(gameCategoryRepository.countByCodeIn(List.of("UNKNOWN_CATEGORY"))).thenReturn(0L);
		when(gameThemeRepository.countByCodeIn(List.of("UNKNOWN_THEME"))).thenReturn(0L);

		assertValidationError(requestWithMechanism(List.of("PRIVATE")));
		assertValidationError(requestWithCategory(List.of("UNKNOWN_CATEGORY")));
		assertValidationError(requestWithTheme(List.of("UNKNOWN_THEME")));

		verifyNoInteractions(semanticGameSearch, gameRepository);
	}

	@Test
	void T2_공개_metadata_code만_정규화하고_ANY_ALL_criteria를_보존한다() {
		SemanticGameSearchRequest request = request("협력 게임");
		request.setMechanism(Arrays.asList(null, "WORKER_PLACEMENT", "WORKER_PLACEMENT"));
		request.setCategory(Arrays.asList(null, "STRATEGY", "STRATEGY"));
		request.setTheme(Arrays.asList(null, "FANTASY", "FANTASY"));
		request.setMechanismMatch(List.of(MechanismMatch.ALL));
		request.setThemeMatch(List.of(ThemeMatch.ANY));
		when(gameMechanismRepository.countByCodeInAndIsPublicTrue(List.of("WORKER_PLACEMENT"))).thenReturn(1L);
		when(gameCategoryRepository.countByCodeIn(List.of("STRATEGY"))).thenReturn(1L);
		when(gameThemeRepository.countByCodeIn(List.of("FANTASY"))).thenReturn(1L);
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class))).thenReturn(emptySemanticResult());

		service.search(request, null);

		verify(gameMechanismRepository).countByCodeInAndIsPublicTrue(List.of("WORKER_PLACEMENT"));
		verify(gameCategoryRepository).countByCodeIn(List.of("STRATEGY"));
		verify(gameThemeRepository).countByCodeIn(List.of("FANTASY"));
		ArgumentCaptor<SemanticGameSearchQuery> queryCaptor = ArgumentCaptor.forClass(SemanticGameSearchQuery.class);
		verify(semanticGameSearch).search(queryCaptor.capture());
		assertEquals(List.of("WORKER_PLACEMENT"), queryCaptor.getValue().criteria().getMechanisms());
		assertEquals(MechanismMatch.ALL, queryCaptor.getValue().criteria().getMechanismMatch());
		assertEquals(List.of("STRATEGY"), queryCaptor.getValue().criteria().getCategories());
		assertEquals(List.of("FANTASY"), queryCaptor.getValue().criteria().getThemes());
		assertEquals(ThemeMatch.ANY, queryCaptor.getValue().criteria().getThemeMatch());
	}

	@Test
	void T2_빈_metadata_filter는_저장소_검증없이_검색_criteria를_비운다() {
		SemanticGameSearchRequest request = request("협력 게임");
		request.setMechanism(List.of());
		request.setCategory(List.of());
		request.setTheme(List.of());
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class))).thenReturn(emptySemanticResult());

		service.search(request, null);

		verifyNoInteractions(gameMechanismRepository, gameCategoryRepository, gameThemeRepository);
		ArgumentCaptor<SemanticGameSearchQuery> queryCaptor = ArgumentCaptor.forClass(SemanticGameSearchQuery.class);
		verify(semanticGameSearch).search(queryCaptor.capture());
		assertEquals(List.of(), queryCaptor.getValue().criteria().getMechanisms());
		assertEquals(List.of(), queryCaptor.getValue().criteria().getCategories());
		assertEquals(List.of(), queryCaptor.getValue().criteria().getThemes());
	}

	@Test
	void T4_유효한_hard_filter는_검증뒤_기존_no_result_페이지_의미를_유지한다() {
		SemanticGameSearchRequest request = request("협력 게임");
		request.setMechanism(List.of("WORKER_PLACEMENT"));
		request.setCategory(List.of("STRATEGY"));
		request.setTheme(List.of("FANTASY"));
		request.setPage(2);
		request.setSize(5);
		when(gameMechanismRepository.countByCodeInAndIsPublicTrue(List.of("WORKER_PLACEMENT"))).thenReturn(1L);
		when(gameCategoryRepository.countByCodeIn(List.of("STRATEGY"))).thenReturn(1L);
		when(gameThemeRepository.countByCodeIn(List.of("FANTASY"))).thenReturn(1L);
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class))).thenReturn(emptySemanticResult());

		SemanticGameSearchResponse response = service.search(request, null);

		assertEquals(List.of(), response.content());
		assertEquals(2, response.page());
		assertEquals(5, response.size());
		InOrder invocationOrder = org.mockito.Mockito.inOrder(
			gameMechanismRepository, gameCategoryRepository, gameThemeRepository, semanticGameSearch);
		invocationOrder.verify(gameMechanismRepository).countByCodeInAndIsPublicTrue(List.of("WORKER_PLACEMENT"));
		invocationOrder.verify(gameCategoryRepository).countByCodeIn(List.of("STRATEGY"));
		invocationOrder.verify(gameThemeRepository).countByCodeIn(List.of("FANTASY"));
		invocationOrder.verify(semanticGameSearch).search(any(SemanticGameSearchQuery.class));
		verifyNoInteractions(gameRepository);
	}

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

	@Test
	void 인증된_사용자의_playedFilter_요청은_core를_호출하고_필터값으로_playedByMe를_표시한다() {
		Game game = entityGame(50L, 1050L, "인증 사용자 게임");
		SemanticGameSearchRequest request = request("협력 게임");
		request.setPlayedFilter(List.of(PlayedFilter.PLAYED_ONLY));
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class))).thenReturn(
			new SemanticGameSearchResult(
				SemanticGameSearchMode.SEMANTIC,
				List.of(new GameSummary(game.getId(), game.getBggId(), game.getName())),
				false));
		when(gameRepository.findAllById(anyList())).thenReturn(List.of(game));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(anyList(), any())).thenReturn(Map.of());

		SemanticGameSearchResponse response = service.search(request, 7L);

		assertTrue(response.content().get(0).playedByMe());
		verifyNoInteractions(userPlayedGameRepository);
	}

	@Test
	void 인증된_사용자의_해본게임_제외_playedFilter는_false로_표시한다() {
		Game game = entityGame(55L, 1055L, "제외 필터 게임");
		SemanticGameSearchRequest request = request("협력 게임");
		request.setPlayedFilter(List.of(PlayedFilter.EXCLUDE_PLAYED));
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class))).thenReturn(
			new SemanticGameSearchResult(
				SemanticGameSearchMode.SEMANTIC,
				List.of(new GameSummary(game.getId(), game.getBggId(), game.getName())),
				false));
		when(gameRepository.findAllById(anyList())).thenReturn(List.of(game));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(anyList(), any())).thenReturn(Map.of());

		SemanticGameSearchResponse response = service.search(request, 7L);

		assertFalse(response.content().get(0).playedByMe());
	}

	@Test
	void 인증된_사용자가_playedFilter_없이_조회하면_실제_플레이_여부를_조회해_표시한다() {
		Game game = entityGame(60L, 1060L, "실제 플레이 조회 게임");
		SemanticGameSearchRequest request = request("협력 게임");
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class))).thenReturn(
			new SemanticGameSearchResult(
				SemanticGameSearchMode.SEMANTIC,
				List.of(new GameSummary(game.getId(), game.getBggId(), game.getName())),
				false));
		when(gameRepository.findAllById(anyList())).thenReturn(List.of(game));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(anyList(), any())).thenReturn(Map.of());
		when(userPlayedGameRepository.findGameIdsByUserIdAndGameIdIn(7L, List.of(game.getId())))
			.thenReturn(List.of(game.getId()));

		SemanticGameSearchResponse response = service.search(request, 7L);

		assertTrue(response.content().get(0).playedByMe());
		verify(userPlayedGameRepository).findGameIdsByUserIdAndGameIdIn(7L, List.of(game.getId()));
	}

	@Test
	void 인증된_사용자여도_결과가_모두_존재하지_않는_게임이면_플레이_여부를_조회하지_않는다() {
		SemanticGameSearchRequest request = request("협력 게임");
		when(semanticGameSearch.search(any(SemanticGameSearchQuery.class))).thenReturn(
			new SemanticGameSearchResult(
				SemanticGameSearchMode.SEMANTIC, List.of(new GameSummary(999L, 1999L, "삭제된 게임")), false));
		when(gameRepository.findAllById(anyList())).thenReturn(List.of());

		SemanticGameSearchResponse response = service.search(request, 7L);

		assertEquals(List.of(), response.content());
		verifyNoInteractions(upcomingRoomCountQuery);
		verifyNoInteractions(userPlayedGameRepository);
	}

	private SemanticGameSearchRequest request(String query) {
		SemanticGameSearchRequest request = new SemanticGameSearchRequest();
		request.setQuery(query);
		return request;
	}

	private SemanticGameSearchRequest requestWithMechanism(List<String> mechanism) {
		SemanticGameSearchRequest request = request("협력 게임");
		request.setMechanism(mechanism);
		return request;
	}

	private SemanticGameSearchRequest requestWithCategory(List<String> category) {
		SemanticGameSearchRequest request = request("협력 게임");
		request.setCategory(category);
		return request;
	}

	private SemanticGameSearchRequest requestWithTheme(List<String> theme) {
		SemanticGameSearchRequest request = request("협력 게임");
		request.setTheme(theme);
		return request;
	}

	private void assertValidationError(SemanticGameSearchRequest request) {
		BusinessException exception = assertThrows(BusinessException.class, () -> service.search(request, null));
		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
	}

	private SemanticGameSearchResult emptySemanticResult() {
		return new SemanticGameSearchResult(SemanticGameSearchMode.SEMANTIC, List.of(), false);
	}

	private Game entityGame(long id, long bggId, String name) {
		Game game = GameFixture.valid(bggId, name);
		ReflectionTestUtils.setField(game, "id", id);
		return game;
	}
}
