package cloud.bamsongi.albammate.chat.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.chat.dto.ChatMessageResponse;
import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.service.ChatMessageCommandService;
import cloud.bamsongi.albammate.chat.service.ChatMessageSendResult;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/** 인증된 채팅 관계자의 메시지 저장 HTTP 경계다. */
@RestController
@RequestMapping("/api/rooms/{roomId}/chat/messages")
@RequiredArgsConstructor
public class ChatMessageController {

	private final ChatMessageCommandService chatMessageCommandService;
	private final CurrentUserAccessor currentUserAccessor;

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<ChatMessageResponse>> send(
		@PathVariable @Positive long roomId,
		@RequestBody
		ChatMessageSendRequest request) {
		ChatMessageSendResult result = chatMessageCommandService.send(
			currentUserAccessor.requireCurrentUserId(), roomId, request);
		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(ApiResponse.success(status, result.message()));
	}
}
