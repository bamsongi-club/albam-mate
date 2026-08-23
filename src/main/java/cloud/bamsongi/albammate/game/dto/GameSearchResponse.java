package cloud.bamsongi.albammate.game.dto;

import java.util.List;

/**
 * #1028 SEARCH-04 공개 게임 검색 응답이다.
 *
 * <p>{@code GET /api/games/search}의 공개 계약으로, 구현 방식(Dense/Sparse/Lexical)과 {@code searchMode}를
 * 노출하지 않는다. 결과가 없으면 빈 {@code content}와 {@code hasNext=false}를 반환한다.
 */
public record GameSearchResponse(List<GameListItem> content, int page, int size, boolean hasNext) {
}
