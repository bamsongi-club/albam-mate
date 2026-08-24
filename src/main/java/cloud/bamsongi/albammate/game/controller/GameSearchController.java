package cloud.bamsongi.albammate.game.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.game.dto.GameSearchResponse;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchRequest;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchResponse;
import cloud.bamsongi.albammate.game.service.SemanticGameSearchQueryService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * #1028 SEARCH-04 공개 게임 검색 계약이다.
 *
 * <p>{@code GET /api/games/search}으로 공개 경로를 단일화하고, 구현 방식(Dense/Sparse/Lexical)을 응답에서
 * 제거한다. 내부 {@link SemanticGameSearchQueryService}를 재사용하되, 공개 응답은
 * {@link GameSearchResponse}로 변환해 {@code searchMode}가 노출되지 않도록 한다.
 *
 * <p>기존 {@code GET /api/games}의 이름 부분일치·응답 계약은 이 변경으로 바꾸지 않는다.
 */
@RestController
@RequestMapping("/api/games/search")
@RequiredArgsConstructor
public class GameSearchController {

	@NonNull private final SemanticGameSearchQueryService semanticGameSearchQueryService;
	@NonNull private final CurrentUserAccessor currentUserAccessor;

	@GetMapping
	public ApiResponse<GameSearchResponse> search(
		@Valid @ModelAttribute
		SemanticGameSearchRequest request) {
		SemanticGameSearchResponse internal = semanticGameSearchQueryService.search(
			request, currentUserAccessor.currentUserId().orElse(null));
		return ApiResponse.success(HttpStatus.OK, toPublicResponse(internal));
	}

	private static GameSearchResponse toPublicResponse(SemanticGameSearchResponse internal) {
		return new GameSearchResponse(internal.content(), internal.page(), internal.size(), internal.hasNext());
	}
}
