package cloud.bamsongi.albammate.room.controller;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.MyRoomListItem;
import cloud.bamsongi.albammate.room.enums.MyRoomRole;
import cloud.bamsongi.albammate.room.service.MyRoomQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 현재 인증 사용자의 참가·주최 방 목록만 노출한다. */
@RestController
@RequestMapping("/api/users/me/rooms")
@Validated
public class MyRoomController {

    private static final Set<String> MY_ROOM_PARAMETERS = Set.of("role", "page", "size");

    private final MyRoomQueryService myRoomQueryService;
    private final CurrentUserAccessor currentUserAccessor;

    public MyRoomController(
            MyRoomQueryService myRoomQueryService, CurrentUserAccessor currentUserAccessor) {
        this.myRoomQueryService = Objects.requireNonNull(myRoomQueryService, "myRoomQueryService");
        this.currentUserAccessor =
                Objects.requireNonNull(currentUserAccessor, "currentUserAccessor");
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<PageResponse<MyRoomListItem>> listMyRooms(
            @RequestParam MyRoomRole role,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        validateParameterNames(request);
        return ApiResponse.success(
                HttpStatus.OK,
                myRoomQueryService.findPage(
                        currentUserAccessor.requireCurrentUserId(), role, page, size));
    }

    private void validateParameterNames(HttpServletRequest request) {
        if (!MY_ROOM_PARAMETERS.containsAll(request.getParameterMap().keySet())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
