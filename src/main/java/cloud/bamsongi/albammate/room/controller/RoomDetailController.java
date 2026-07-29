package cloud.bamsongi.albammate.room.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.RoomDetailResponse;
import cloud.bamsongi.albammate.room.service.RoomDetailService;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/api/rooms")
public class RoomDetailController {

	private final RoomDetailService roomDetailService;
	private final CurrentUserAccessor currentUserAccessor;

	public RoomDetailController(
		RoomDetailService roomDetailService, CurrentUserAccessor currentUserAccessor) {
		this.roomDetailService = roomDetailService;
		this.currentUserAccessor = currentUserAccessor;
	}

	@GetMapping(path = "/{roomId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<RoomDetailResponse>> getRoomDetail(
		@PathVariable @Positive long roomId) {
		RoomDetailResponse response = roomDetailService.findRoomDetail(roomId, currentUserAccessor.currentUserId());
		return ResponseEntity.ok()
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.header(HttpHeaders.VARY, "Cookie")
			.body(ApiResponse.success(HttpStatus.OK, response));
	}
}
