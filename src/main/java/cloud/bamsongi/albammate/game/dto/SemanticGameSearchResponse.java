package cloud.bamsongi.albammate.game.dto;

import java.util.List;

import cloud.bamsongi.albammate.game.contract.SemanticGameSearchMode;

/**
 * SEARCH-04 의미 검색 공개 응답이다.
 *
 * <p>{@code searchMode}는 {@code SEMANTIC} 또는 {@code LEXICAL_FALLBACK}만 담는다. {@code UNAVAILABLE}은
 * {@code 503 SEARCH_UNAVAILABLE} 오류로만 표현하고 성공 응답으로 포장하지 않는다. relevance 점수·embedding·
 * 사용자가 입력한 원문은 담지 않는다.
 */
public record SemanticGameSearchResponse(
	List<GameListItem> content, int page, int size, boolean hasNext, SemanticGameSearchMode searchMode) {
}
