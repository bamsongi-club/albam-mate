package cloud.bamsongi.albammate.room.controller;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.MyRoomWaitlistResponse;
import cloud.bamsongi.albammate.room.service.command.RoomWaitlistCommandService;
import cloud.bamsongi.albammate.room.service.query.RoomWaitlistQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 대기 등록·본인 상태 조회·대기 취소의 HTTP 경계만 소유한다. */
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomWaitlistController {

	@NonNull private final RoomWaitlistCommandService commandService;
	@NonNull private final RoomWaitlistQueryService queryService;
	@NonNull private final CurrentUserAccessor currentUserAccessor;

	@PostMapping(value = "/{roomId}/waitlist", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<MyRoomWaitlistResponse>> register(
		@PathVariable @Positive long roomId, HttpServletRequest request) throws IOException {
		requireEmptyRequest(request);
		long currentUserId = currentUserAccessor.requireCurrentUserId();
		RoomWaitlistCommandService.RegistrationResult result = commandService.register(currentUserId, roomId);
		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(ApiResponse.success(status, result.response()));
	}

	@GetMapping(value = "/{roomId}/waitlist/me", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<MyRoomWaitlistResponse>> findMyWaitlist(
		@PathVariable @Positive long roomId, HttpServletRequest request) throws IOException {
		requireEmptyRequest(request);
		return ResponseEntity.ok(ApiResponse.success(
			HttpStatus.OK,
			queryService.findMyWaitlist(currentUserAccessor.requireCurrentUserId(), roomId)));
	}

	@DeleteMapping(value = "/{roomId}/waitlist/me", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> cancel(
		@PathVariable @Positive long roomId, HttpServletRequest request) throws IOException {
		requireEmptyRequest(request);
		commandService.cancel(currentUserAccessor.requireCurrentUserId(), roomId);
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK));
	}

	private static void requireEmptyRequest(HttpServletRequest request) throws IOException {
		boolean hasContentType = request.getContentType() != null;
		boolean hasRequestBody = request.getInputStream().read() != -1;

		if (hasContentType || hasRequestBody) {
			throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
		}
	}
}
