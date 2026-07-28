package cloud.bamsongi.albammate.room.controller;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.global.security.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.CreateRoomRequest;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.PublicRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.dto.RoomStatusResponse;
import cloud.bamsongi.albammate.room.dto.RoomStatusUpdateRequest;
import cloud.bamsongi.albammate.room.dto.RoomUpdateRequest;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.service.RoomCreateService;
import cloud.bamsongi.albammate.room.service.RoomListQueryService;
import cloud.bamsongi.albammate.room.service.RoomParticipationService;
import cloud.bamsongi.albammate.room.service.RoomStatusChangeService;
import cloud.bamsongi.albammate.room.service.RoomUpdateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
@Validated
public class RoomController {

    private static final Set<String> ROOM_LIST_PARAMETERS =
            Set.of("type", "gameId", "keyword", "page", "size");

    private final RoomCreateService roomCreateService;
    private final RoomListQueryService roomListQueryService;
    private final RoomParticipationService roomParticipationService;
    private final RoomUpdateService roomUpdateService;
    private final RoomStatusChangeService roomStatusChangeService;
    private final CurrentUserAccessor currentUserAccessor;

    public RoomController(
            RoomCreateService roomCreateService,
            RoomListQueryService roomListQueryService,
            RoomParticipationService roomParticipationService,
            RoomUpdateService roomUpdateService,
            RoomStatusChangeService roomStatusChangeService,
            CurrentUserAccessor currentUserAccessor) {
        this.roomCreateService = roomCreateService;
        this.roomListQueryService = roomListQueryService;
        this.roomParticipationService = roomParticipationService;
        this.roomUpdateService = roomUpdateService;
        this.roomStatusChangeService = roomStatusChangeService;
        this.currentUserAccessor = currentUserAccessor;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<PageResponse<PublicRoomResponse>> listRooms(
            @RequestParam RoomType type,
            @RequestParam(required = false) @Min(1) Long gameId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        validateListRequest(type, gameId, request);
        return ApiResponse.success(
                HttpStatus.OK,
                roomListQueryService.findPage(
                        type, gameId, keyword, page, size, currentUserAccessor.currentUserId()));
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

    private void validateListRequest(RoomType type, Long gameId, HttpServletRequest request) {
        Set<String> parameterNames = request.getParameterMap().keySet();
        if (!ROOM_LIST_PARAMETERS.containsAll(parameterNames)
                || (type == RoomType.GAME_FOCUSED
                        && (!parameterNames.contains("gameId")
                                || gameId == null
                                || parameterNames.contains("keyword")))
                || (type == RoomType.PERSON_FOCUSED && parameterNames.contains("gameId"))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
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

    @DeleteMapping(path = "/{roomId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RoomStatusResponse> cancelRoom(@PathVariable @Positive long roomId) {
        RoomStatusResponse response =
                roomStatusChangeService.cancelRoom(
                        currentUserAccessor.requireCurrentUserId(), roomId);
        return ApiResponse.success(HttpStatus.OK, response);
    }

    @PatchMapping(
            path = "/{roomId}/status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RoomStatusResponse> finishRoom(
            @PathVariable @Positive long roomId,
            @Valid @RequestBody RoomStatusUpdateRequest request) {
        RoomStatusResponse response =
                roomStatusChangeService.finishRoom(
                        currentUserAccessor.requireCurrentUserId(), roomId);
        return ApiResponse.success(HttpStatus.OK, response);
    }
}
