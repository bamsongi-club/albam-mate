package cloud.bamsongi.albammate.game.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.game.dto.SemanticGameSearchRequest;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchResponse;
import cloud.bamsongi.albammate.game.service.SemanticGameSearchQueryService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * #871 SEARCH-04 공개 의미 검색 read 계약이다.
 *
 * <p>기존 {@code GET /api/games}의 이름 부분일치·응답 계약은 바꾸지 않고 별도 경로로 추가한다.
 */
@RestController
@RequestMapping("/api/games/semantic-search")
@RequiredArgsConstructor
public class SemanticGameSearchController {

	@NonNull private final SemanticGameSearchQueryService semanticGameSearchQueryService;
	@NonNull private final CurrentUserAccessor currentUserAccessor;

	@GetMapping
	public ApiResponse<SemanticGameSearchResponse> search(
		@Valid @ModelAttribute
		SemanticGameSearchRequest request) {
		SemanticGameSearchResponse response = semanticGameSearchQueryService.search(
			request, currentUserAccessor.currentUserId().orElse(null));
		return ApiResponse.success(HttpStatus.OK, response);
	}
}
