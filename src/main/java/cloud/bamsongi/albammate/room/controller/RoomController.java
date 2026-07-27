package cloud.bamsongi.albammate.room.controller;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.CreateRoomRequest;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.service.RoomCreateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomCreateService roomCreateService;
    private final CurrentUserAccessor currentUserAccessor;

    public RoomController(
            RoomCreateService roomCreateService, CurrentUserAccessor currentUserAccessor) {
        this.roomCreateService = roomCreateService;
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
}
