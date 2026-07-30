package cloud.bamsongi.albammate.room.controller;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.service.RoomParticipationCancelService;
import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/rooms")
@Slf4j
public class RoomParticipationController {

	private final RoomParticipationCancelService roomParticipationCancelService;
	private final CurrentUserAccessor currentUserAccessor;

	public RoomParticipationController(
		RoomParticipationCancelService roomParticipationCancelService,
		CurrentUserAccessor currentUserAccessor) {
		this.roomParticipationCancelService = Objects.requireNonNull(
			roomParticipationCancelService, "roomParticipationCancelService");
		this.currentUserAccessor = Objects.requireNonNull(currentUserAccessor, "currentUserAccessor");
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
