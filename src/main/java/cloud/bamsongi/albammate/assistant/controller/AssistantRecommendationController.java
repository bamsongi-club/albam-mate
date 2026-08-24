package cloud.bamsongi.albammate.assistant.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.assistant.dto.AssistantRecommendationRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantRecommendationResponse;
import cloud.bamsongi.albammate.assistant.service.AssistantIntentOrchestrationService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 동의한 사용자의 자연어 추천 요청을 game 후보 조회 경계로 전달한다. */
@RestController
@RequestMapping("/api/assistant/recommendations")
@RequiredArgsConstructor
public class AssistantRecommendationController {

	private final AssistantIntentOrchestrationService orchestrationService;
	private final CurrentUserAccessor currentUserAccessor;

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<AssistantRecommendationResponse>> recommend(
		@Valid @RequestBody
		AssistantRecommendationRequest request) {
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK,
			orchestrationService.recommend(currentUserAccessor.requireCurrentUserId(), request)));
	}
}
