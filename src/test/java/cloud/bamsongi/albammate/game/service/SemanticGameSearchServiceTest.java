package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchMode;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchQuery;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchResult;
import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameRepository;

class SemanticGameSearchServiceTest {

	@Test
	void T2_filtered_결과수_밖의_페이지는_빈_SEMANTIC과_hasNext_false를_반환한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		Game game = game(10L, 1010L, "전략 게임");
		when(candidateSource.findCandidates(anyString()))
			.thenReturn(List.of(new DenseCandidateSource.Candidate(game.getId(), 0.9)));
		when(gameRepository.findAll(any(Specification.class))).thenReturn(List.of(game));

		SemanticGameSearchResult result = service(gameRepository, candidateSource).search(query("전략 게임", 1, 1));

		assertEquals(SemanticGameSearchMode.SEMANTIC, result.mode());
		assertEquals(List.of(), result.content());
		assertFalse(result.hasNext());
	}

	@Test
	void T3_dense_정상_빈_결과는_필터완화나_이름검색없이_빈_SEMANTIC으로_반환한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		when(candidateSource.findCandidates(anyString())).thenReturn(List.of());

		SemanticGameSearchResult result = service(gameRepository, candidateSource).search(query("전략 게임"));

		assertEquals(SemanticGameSearchMode.SEMANTIC, result.mode());
		assertEquals(List.of(), result.content());
		assertFalse(result.hasNext());
	}

	@Test
	void T4_dense_실패면_같은_필터의_LEXICAL_FALLBACK을_반환한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		GameSummary lexicalMatch = new GameSummary(11L, 1011L, "전략 게임");
		when(candidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(gameRepository.findLexicalFallbackSummaries(any(Specification.class), any()))
			.thenReturn(new SliceImpl<>(List.of(lexicalMatch), PageRequest.of(1, 1), true));

		SemanticGameSearchResult result = service(gameRepository, candidateSource).search(query("전략 게임", 1, 1));

		assertEquals(SemanticGameSearchMode.LEXICAL_FALLBACK, result.mode());
		assertEquals(List.of(lexicalMatch.id()), result.content().stream().map(game -> game.id()).toList());
		assertTrue(result.hasNext());
		ArgumentCaptor<PageRequest> pageable = ArgumentCaptor.forClass(PageRequest.class);
		org.mockito.Mockito.verify(gameRepository).findLexicalFallbackSummaries(any(Specification.class),
			pageable.capture());
		assertEquals(1, pageable.getValue().getPageNumber());
		assertEquals(1, pageable.getValue().getPageSize());
	}

	@Test
	void T5_dense와_lexical이_모두_불능이면_원문과_상세없는_UNAVAILABLE만_반환한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		String rawQuery = "사용자 비밀 query";
		when(candidateSource.findCandidates(rawQuery)).thenThrow(new SemanticSearchUnavailableException());
		when(gameRepository.findLexicalFallbackSummaries(any(Specification.class), any()))
			.thenThrow(new DataAccessResourceFailureException("database detail"));

		SemanticGameSearchResult result = service(gameRepository, candidateSource).search(query(rawQuery));

		assertEquals(SemanticGameSearchMode.UNAVAILABLE, result.mode());
		assertEquals(List.of(), result.content());
		assertFalse(result.toString().contains(rawQuery));
		assertFalse(result.toString().contains("database detail"));
	}

	@Test
	void T4_dense_source의_예상하지_않은_오류는_lexical_fallback으로_숨기지_않는다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		when(candidateSource.findCandidates(anyString())).thenThrow(new IllegalStateException("candidate bug"));

		assertThrows(IllegalStateException.class,
			() -> service(gameRepository, candidateSource).search(query("전략 게임")));
	}

	@Test
	void T4_lexical_fallback은_DB_Slice_GameSummary로_P1_페이지경계를_위임한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		GameSummary popular = new GameSummary(22L, 1022L, "가나다");
		when(candidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(gameRepository.findLexicalFallbackSummaries(any(Specification.class), any()))
			.thenReturn(new SliceImpl<>(List.of(popular), PageRequest.of(0, 1), true));

		SemanticGameSearchResult result = service(gameRepository, candidateSource).search(query("전략 게임", 0, 1));

		assertEquals(SemanticGameSearchMode.LEXICAL_FALLBACK, result.mode());
		assertEquals(List.of(popular), result.content());
		assertTrue(result.hasNext());
		org.mockito.Mockito.verify(gameRepository, org.mockito.Mockito.never()).findAll(any(Specification.class));
	}

	@Test
	void T5_lexical_DB의_예상하지_않은_오류는_UNAVAILABLE로_숨기지_않는다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		when(candidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(gameRepository.findLexicalFallbackSummaries(any(Specification.class), any()))
			.thenThrow(new IllegalStateException("repository bug"));

		assertThrows(IllegalStateException.class,
			() -> service(gameRepository, candidateSource).search(query("전략 게임")));
	}

	@Test
	void T2_semantic_후보_재검증_저장소오류는_fallback으로_숨기지_않는다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		when(candidateSource.findCandidates(anyString()))
			.thenReturn(List.of(new DenseCandidateSource.Candidate(1L, 0.9)));
		when(gameRepository.findAll(any(Specification.class))).thenThrow(new IllegalStateException("repository bug"));

		assertThrows(IllegalStateException.class,
			() -> service(gameRepository, candidateSource).search(query("전략 게임")));
	}

	@Test
	void T6_내부_결과계약은_점수_vector_query를_노출하지_않고_기존_P1_목록과_분리한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		Game game = game(12L, 1012L, "기존 목록 게임");
		when(candidateSource.findCandidates(anyString()))
			.thenReturn(List.of(new DenseCandidateSource.Candidate(game.getId(), 0.9)));
		when(gameRepository.findAll(any(Specification.class))).thenReturn(List.of(game));

		SemanticGameSearchResult result = service(gameRepository, candidateSource).search(query("기존 목록"));

		assertEquals(SemanticGameSearchMode.SEMANTIC, result.mode());
		assertEquals(List.of(game.getId()), result.content().stream().map(item -> item.id()).toList());
		List<String> resultFields = List.of(SemanticGameSearchResult.class.getRecordComponents()).stream()
			.map(component -> component.getName()).toList();
		assertEquals(List.of("mode", "content", "hasNext"), resultFields);
		assertTrue(resultFields.stream().noneMatch(field -> field.contains("score") || field.contains("vector")
			|| field.contains("query")));
	}

	private SemanticGameSearchService service(
		GameRepository gameRepository, DenseCandidateSource candidateSource) {
		return new SemanticGameSearchService(gameRepository, candidateSource);
	}

	private SemanticGameSearchQuery query(String rawQuery) {
		return query(rawQuery, 0, 10);
	}

	private SemanticGameSearchQuery query(String rawQuery, int page, int size) {
		return new SemanticGameSearchQuery(rawQuery, GameListSearchCriteria.from(new GameListRequest()), page, size);
	}

	private Game game(long id, long bggId, String name) {
		Game game = new Game(bggId, name, name, "2~4명", "전략", "30분", "설명", "상세 설명");
		ReflectionTestUtils.setField(game, "id", id);
		ReflectionTestUtils.setField(game, "popularityScore", BigDecimal.ZERO);
		return game;
	}
}
