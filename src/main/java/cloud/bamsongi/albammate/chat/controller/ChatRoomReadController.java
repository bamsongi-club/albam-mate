package cloud.bamsongi.albammate.chat.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.chat.dto.ChatRoomReadRequest;
import cloud.bamsongi.albammate.chat.dto.ChatRoomReadStateResponse;
import cloud.bamsongi.albammate.chat.service.ChatRoomReadService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/** 인증된 채팅 관계자가 방을 읽음 처리하는 HTTP 경계다. */
@RestController
@RequestMapping("/api/rooms/{roomId}/chat")
@RequiredArgsConstructor
public class ChatRoomReadController {

	private final ChatRoomReadService chatRoomReadService;
	private final CurrentUserAccessor currentUserAccessor;

	@PostMapping(path = "/read", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<ChatRoomReadStateResponse>> markRead(
		@PathVariable @Positive long roomId,
		@RequestBody
		ChatRoomReadRequest request) {
		ChatRoomReadStateResponse response = chatRoomReadService.markRead(
			currentUserAccessor.requireCurrentUserId(), roomId, request.upToMessageId());
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, response));
	}
}
