package cloud.bamsongi.albammate.room.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.CreateRoomRequest;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.PublicRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomDetailResponse;
import cloud.bamsongi.albammate.room.dto.RoomListRequest;
import cloud.bamsongi.albammate.room.dto.RoomStatusResponse;
import cloud.bamsongi.albammate.room.dto.RoomStatusUpdateRequest;
import cloud.bamsongi.albammate.room.dto.RoomUpdateRequest;
import cloud.bamsongi.albammate.room.service.command.RoomCreateService;
import cloud.bamsongi.albammate.room.service.command.RoomStatusChangeService;
import cloud.bamsongi.albammate.room.service.command.RoomUpdateService;
import cloud.bamsongi.albammate.room.service.query.RoomDetailService;
import cloud.bamsongi.albammate.room.service.query.RoomListQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/rooms")
@Slf4j
public class RoomController {

	private final RoomCreateService roomCreateService;
	private final RoomListQueryService roomListQueryService;
	private final RoomDetailService roomDetailService;
	private final RoomUpdateService roomUpdateService;
	private final RoomStatusChangeService roomStatusChangeService;
	private final CurrentUserAccessor currentUserAccessor;

	public RoomController(
		RoomCreateService roomCreateService,
		RoomListQueryService roomListQueryService,
		RoomDetailService roomDetailService,
		RoomUpdateService roomUpdateService,
		RoomStatusChangeService roomStatusChangeService,
		CurrentUserAccessor currentUserAccessor) {
		this.roomCreateService = roomCreateService;
		this.roomListQueryService = roomListQueryService;
		this.roomDetailService = roomDetailService;
		this.roomUpdateService = roomUpdateService;
		this.roomStatusChangeService = roomStatusChangeService;
		this.currentUserAccessor = currentUserAccessor;
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<PageResponse<PublicRoomResponse>>> listRooms(
		@Valid @ModelAttribute
		RoomListRequest listRequest,
		HttpServletRequest servletRequest) {
		RoomQueryParameterAllowlistValidator.validateRoomList(servletRequest);
		return ResponseEntity.ok(ApiResponse.success(
			HttpStatus.OK,
			roomListQueryService.findPage(
				listRequest.getType(),
				listRequest.getGameId(),
				listRequest.getKeyword(),
				listRequest.getStartsAtFrom(),
				listRequest.getStartsAtTo(),
				listRequest.getMinRemainingSeats(),
				listRequest.getExperienceLevels(),
				listRequest.isRulemasterOnly(),
				listRequest.getPage(),
				listRequest.getSize(),
				currentUserAccessor.currentUserId())));
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

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<ParticipantRoomResponse>> createRoom(
		@Valid @RequestBody
		CreateRoomRequest request) {
		long currentUserId = currentUserAccessor.requireCurrentUserId();
		ParticipantRoomResponse response = roomCreateService.createRoom(currentUserId, request);
		log.info("event=room_created roomId={} actorUserId={} roomStatus={}",
			response.id(), currentUserId, response.status());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(HttpStatus.CREATED, response));
	}

	@PatchMapping(path = "/{roomId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<ParticipantRoomResponse>> updateRoom(
		@PathVariable @Positive long roomId, @Valid @RequestBody
		RoomUpdateRequest request) {
		long currentUserId = currentUserAccessor.requireCurrentUserId();
		ParticipantRoomResponse response = roomUpdateService.updateRoom(
			currentUserId, roomId, request);
		log.info("event=room_updated roomId={} actorUserId={} roomStatus={}",
			response.id(), currentUserId, response.status());
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, response));
	}

	@DeleteMapping(path = "/{roomId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<RoomStatusResponse>> cancelRoom(@PathVariable @Positive long roomId) {
		long currentUserId = currentUserAccessor.requireCurrentUserId();
		RoomStatusResponse response = roomStatusChangeService.cancelRoom(
			currentUserId, roomId);
		log.info("event=room_canceled roomId={} actorUserId={} roomStatus={}",
			response.roomId(), currentUserId, response.roomStatus());
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, response));
	}

	@PatchMapping(path = "/{roomId}/status", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<RoomStatusResponse>> finishRoom(
		@PathVariable @Positive long roomId,
		@Valid @RequestBody
		RoomStatusUpdateRequest request) {
		long currentUserId = currentUserAccessor.requireCurrentUserId();
		RoomStatusResponse response = roomStatusChangeService.finishRoom(
			currentUserId, roomId);
		log.info("event=room_finished roomId={} actorUserId={} roomStatus={}",
			response.roomId(), currentUserId, response.roomStatus());
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, response));
	}
}
