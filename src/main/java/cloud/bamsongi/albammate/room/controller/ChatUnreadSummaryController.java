package cloud.bamsongi.albammate.room.controller;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.UnreadChatSummaryResponse;
import cloud.bamsongi.albammate.room.service.query.ChatUnreadSummaryQueryService;

/** 상단 채팅 아이콘 배지용 미읽음 방 개수 조회 경계다(CHAT-07). */
@RestController
@RequestMapping("/api/users/me/chat")
public class ChatUnreadSummaryController {

	private final ChatUnreadSummaryQueryService chatUnreadSummaryQueryService;
	private final CurrentUserAccessor currentUserAccessor;

	public ChatUnreadSummaryController(
		ChatUnreadSummaryQueryService chatUnreadSummaryQueryService, CurrentUserAccessor currentUserAccessor) {
		this.chatUnreadSummaryQueryService = Objects
			.requireNonNull(chatUnreadSummaryQueryService, "chatUnreadSummaryQueryService");
		this.currentUserAccessor = Objects.requireNonNull(currentUserAccessor, "currentUserAccessor");
	}

	@GetMapping(path = "/unread-summary", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<UnreadChatSummaryResponse>> unreadSummary() {
		long currentUserId = currentUserAccessor.requireCurrentUserId();
		int unreadRoomCount = chatUnreadSummaryQueryService.countUnreadRooms(currentUserId);
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, new UnreadChatSummaryResponse(unreadRoomCount)));
	}
}
