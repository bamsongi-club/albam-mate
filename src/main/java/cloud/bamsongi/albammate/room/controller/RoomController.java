package cloud.bamsongi.albammate.room.controller;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.CreateRoomRequest;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomUpdateRequest;
import cloud.bamsongi.albammate.room.service.RoomCreateService;
import cloud.bamsongi.albammate.room.service.RoomUpdateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final RoomUpdateService roomUpdateService;
    private final CurrentUserAccessor currentUserAccessor;

    public RoomController(
            RoomCreateService roomCreateService,
            RoomUpdateService roomUpdateService,
            CurrentUserAccessor currentUserAccessor) {
        this.roomCreateService = roomCreateService;
        this.roomUpdateService = roomUpdateService;
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

    @PatchMapping(
            path = "/{roomId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ParticipantRoomResponse> updateRoom(
            @PathVariable @Positive long roomId, @Valid @RequestBody RoomUpdateRequest request) {
        ParticipantRoomResponse response =
                roomUpdateService.updateRoom(
                        currentUserAccessor.requireCurrentUserId(), roomId, request);
        return ApiResponse.success(HttpStatus.OK, response);
    }
}
