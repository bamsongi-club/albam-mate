package cloud.bamsongi.albammate.room.controller;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.service.RoomParticipationCancelService;
import jakarta.validation.constraints.Positive;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
@Validated
public class RoomParticipationController {

    private final RoomParticipationCancelService roomParticipationCancelService;
    private final CurrentUserAccessor currentUserAccessor;

    public RoomParticipationController(
            RoomParticipationCancelService roomParticipationCancelService,
            CurrentUserAccessor currentUserAccessor) {
        this.roomParticipationCancelService =
                Objects.requireNonNull(
                        roomParticipationCancelService, "roomParticipationCancelService");
        this.currentUserAccessor =
                Objects.requireNonNull(currentUserAccessor, "currentUserAccessor");
    }

    @DeleteMapping(value = "/{roomId}/participants/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RoomParticipationResponse> cancelParticipation(
            @PathVariable @Positive long roomId) {
        RoomParticipationResponse response =
                roomParticipationCancelService.cancelParticipation(
                        currentUserAccessor.requireCurrentUserId(), roomId);
        return ApiResponse.success(HttpStatus.OK, response);
    }
}
