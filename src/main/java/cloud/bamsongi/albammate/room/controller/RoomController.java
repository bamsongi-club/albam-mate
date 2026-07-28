package cloud.bamsongi.albammate.room.controller;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.CreateRoomRequest;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.service.RoomCreateService;
import cloud.bamsongi.albammate.room.service.RoomParticipationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomCreateService roomCreateService;
    private final RoomParticipationService roomParticipationService;
    private final CurrentUserAccessor currentUserAccessor;

    public RoomController(
            RoomCreateService roomCreateService,
            RoomParticipationService roomParticipationService,
            CurrentUserAccessor currentUserAccessor) {
        this.roomCreateService = roomCreateService;
        this.roomParticipationService = roomParticipationService;
        this.currentUserAccessor = currentUserAccessor;
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ParticipantRoomResponse> createRoom(
            @Valid @RequestBody CreateRoomRequest request) {
        ParticipantRoomResponse response =
                roomCreateService.createRoom(currentUserAccessor.requireCurrentUserId(), request);
        return ApiResponse.success(HttpStatus.CREATED, response);
    }

    @PostMapping(value = "/{roomId}/participants", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RoomParticipationResponse> participate(@PathVariable @Positive long roomId) {
        RoomParticipationResponse response =
                roomParticipationService.participate(
                        currentUserAccessor.requireCurrentUserId(), roomId);
        return ApiResponse.success(HttpStatus.CREATED, response);
    }
}
