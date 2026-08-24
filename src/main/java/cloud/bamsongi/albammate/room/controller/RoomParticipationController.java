package cloud.bamsongi.albammate.room.controller;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/rooms")
@Slf4j
public class RoomParticipationController {

	private final RoomParticipationService roomParticipationService;
	private final RoomParticipationCancelService roomParticipationCancelService;
	private final CurrentUserAccessor currentUserAccessor;

	public RoomParticipationController(
		RoomParticipationService roomParticipationService,
		RoomParticipationCancelService roomParticipationCancelService,
		CurrentUserAccessor currentUserAccessor) {
		this.roomParticipationService = Objects.requireNonNull(roomParticipationService, "roomParticipationService");
		this.roomParticipationCancelService = Objects.requireNonNull(
			roomParticipationCancelService, "roomParticipationCancelService");
		this.currentUserAccessor = Objects.requireNonNull(currentUserAccessor, "currentUserAccessor");
	}

	@PostMapping(value = "/{roomId}/participants", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<RoomParticipationResponse>> participate(@PathVariable @Positive long roomId) {
		long currentUserId = currentUserAccessor.requireCurrentUserId();
		RoomParticipationResponse response = roomParticipationService.participate(currentUserId, roomId);
		log.info("event=room_participation_created roomId={} actorUserId={} roomStatus={}",
			response.roomId(), currentUserId, response.roomStatus());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(HttpStatus.CREATED, response));
	}

	@DeleteMapping(value = "/{roomId}/participants/me", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<RoomParticipationResponse>> cancelParticipation(
		@PathVariable @Positive long roomId) {
		long currentUserId = currentUserAccessor.requireCurrentUserId();
		RoomParticipationResponse response = roomParticipationCancelService.cancelParticipation(
			currentUserId, roomId);
		log.info("event=room_participation_canceled roomId={} actorUserId={} roomStatus={}",
			response.roomId(), currentUserId, response.roomStatus());
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, response));
	}
}
