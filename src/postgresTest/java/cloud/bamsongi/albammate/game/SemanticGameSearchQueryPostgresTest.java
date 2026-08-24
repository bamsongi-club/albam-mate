package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearch;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchMode;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchResult;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchRequest;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchResponse;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.service.SemanticGameSearchQueryService;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

/**
 * #871이 #836 core 결과를 PostgreSQL {@link GameRepository}로 재조회해 순서를 보존하고, 삭제된
 * game id를 결과에서 제외하는지 검증한다. dense/lexical 판정 자체는 이 테스트의 검증 대상이 아니다.
 */
@Testcontainers
@SpringBootTest
@Transactional
class SemanticGameSearchQueryPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private SemanticGameSearchQueryService semanticGameSearchQueryService;
	@Autowired
	private GameRepository gameRepository;
	@MockitoBean
	private SemanticGameSearch semanticGameSearch;

	@Test
	void T3_core_결과의_id_순서를_보존한_채_PostgreSQL에서_GameListItem_상세로_재조립한다() {
		Game second = game(871_201L, "두번째 게임");
		Game first = game(871_202L, "첫번째 게임");
		when(semanticGameSearch.search(any())).thenReturn(
			new SemanticGameSearchResult(
				SemanticGameSearchMode.SEMANTIC,
				List.of(
					new GameSummary(first.getId(), first.getBggId(), first.getName()),
					new GameSummary(second.getId(), second.getBggId(), second.getName())),
				false));

		SemanticGameSearchResponse response = semanticGameSearchQueryService.search(request("협력 게임"), null);

		assertEquals(List.of(first.getId(), second.getId()),
			response.content().stream().map(item -> item.id()).toList());
		assertEquals(List.of(first.getName(), second.getName()),
			response.content().stream().map(item -> item.name()).toList());
	}

	@Test
	void T3_삭제되어_존재하지_않는_game_id는_PostgreSQL_재조회에서_제외한다() {
		Game existing = game(871_301L, "존재하는 게임");
		long deletedGameId = 871_302L;
		when(semanticGameSearch.search(any())).thenReturn(
			new SemanticGameSearchResult(
				SemanticGameSearchMode.SEMANTIC,
				List.of(
					new GameSummary(deletedGameId, 999_302L, "삭제된 게임"),
					new GameSummary(existing.getId(), existing.getBggId(), existing.getName())),
				false));

		SemanticGameSearchResponse response = semanticGameSearchQueryService.search(request("협력 게임"), null);

		assertEquals(List.of(existing.getId()), response.content().stream().map(item -> item.id()).toList());
	}

	@Test
	void T4_hard_filter를_만족하는_후보가_없으면_PostgreSQL_조회없이_빈_페이지다() {
		when(semanticGameSearch.search(any()))
			.thenReturn(new SemanticGameSearchResult(SemanticGameSearchMode.SEMANTIC, List.of(), false));

		SemanticGameSearchResponse response = semanticGameSearchQueryService.search(request("존재하지 않는 조합"), null);

		assertEquals(List.of(), response.content());
		assertFalse(response.hasNext());
	}

	@Test
	void T2_비로그인_playedFilter_요청은_PostgreSQL_조회없이_UNAUTHENTICATED다() {
		SemanticGameSearchRequest request = request("협력 게임");
		request.setPlayedFilter(List.of(PlayedFilter.PLAYED_ONLY));

		assertThrows(UnauthenticatedException.class,
			() -> semanticGameSearchQueryService.search(request, null));
	}

	@Test
	void T5_core가_UNAVAILABLE이면_503_SEARCH_UNAVAILABLE로_던진다() {
		when(semanticGameSearch.search(any()))
			.thenReturn(new SemanticGameSearchResult(SemanticGameSearchMode.UNAVAILABLE, List.of(), false));

		BusinessException exception = assertThrows(BusinessException.class,
			() -> semanticGameSearchQueryService.search(request("협력 게임"), null));

		assertEquals(ErrorCode.SEARCH_UNAVAILABLE, exception.getErrorCode());
	}

	private SemanticGameSearchRequest request(String query) {
		SemanticGameSearchRequest request = new SemanticGameSearchRequest();
		request.setQuery(query);
		return request;
	}

	private Game game(long bggId, String name) {
		Game game = new Game(bggId, name, name, "2~4명", "전략", "30분", "설명", "상세 설명");
		ReflectionTestUtils.setField(game, "complexity", new BigDecimal("2.00"));
		return gameRepository.saveAndFlush(game);
	}
}
