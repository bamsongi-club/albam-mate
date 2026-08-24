package cloud.bamsongi.albammate.chat.match;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.chat.match.service.MatchChatMessageCommandService;
import cloud.bamsongi.albammate.chat.match.service.MatchChatMessageHistoryQueryService;
import cloud.bamsongi.albammate.chat.match.service.MatchChatMessageSendResult;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/** 인증된 MATCH 성공 파티 관계자의 채팅 메시지 저장·이력 조회 HTTP 경계다. */
@RestController
@RequestMapping("/api/matches/parties/{partyId}/chat/messages")
@RequiredArgsConstructor
public class MatchChatMessageController {

	private final MatchChatMessageCommandService matchChatMessageCommandService;
	private final MatchChatMessageHistoryQueryService matchChatMessageHistoryQueryService;
	private final CurrentUserAccessor currentUserAccessor;

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<MatchChatMessageResponse>> send(
		@PathVariable @Positive long partyId,
		@RequestBody
		MatchChatMessageSendRequest request) {
		MatchChatMessageSendResult result = matchChatMessageCommandService.send(
			currentUserAccessor.requireCurrentUserId(), partyId, request);
		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(ApiResponse.success(status, result.message()));
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<MatchChatMessagePageResponse>> history(
		@PathVariable @Positive long partyId,
		@RequestParam(required = false) @Positive Long beforeMessageId,
		@RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
		MatchChatMessagePageResponse page = matchChatMessageHistoryQueryService.history(
			currentUserAccessor.requireCurrentUserId(), partyId, beforeMessageId, size);
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, page));
	}
}
