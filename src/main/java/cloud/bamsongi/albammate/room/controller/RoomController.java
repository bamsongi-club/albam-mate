package cloud.bamsongi.albammate.room.controller;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.CreateRoomRequest;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomPageResponse;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.service.RoomCreateService;
import cloud.bamsongi.albammate.room.service.RoomListQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final CurrentUserAccessor currentUserAccessor;

    public RoomController(
            RoomCreateService roomCreateService,
            RoomListQueryService roomListQueryService,
            CurrentUserAccessor currentUserAccessor) {
        this.roomCreateService = roomCreateService;
        this.roomListQueryService = roomListQueryService;
        this.currentUserAccessor = currentUserAccessor;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RoomPageResponse> listRooms(
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
}
