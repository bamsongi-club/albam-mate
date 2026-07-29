package cloud.bamsongi.albammate.room.controller;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.dto.MyRoomListItem;
import cloud.bamsongi.albammate.room.dto.MyRoomListRequest;
import cloud.bamsongi.albammate.room.service.MyRoomQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** 현재 인증 사용자의 참가·주최 방 목록만 노출한다. */
@RestController
@RequestMapping("/api/users/me/rooms")
public class MyRoomController {

	private final MyRoomQueryService myRoomQueryService;
	private final CurrentUserAccessor currentUserAccessor;

	public MyRoomController(
		MyRoomQueryService myRoomQueryService, CurrentUserAccessor currentUserAccessor) {
		this.myRoomQueryService = Objects.requireNonNull(myRoomQueryService, "myRoomQueryService");
		this.currentUserAccessor = Objects.requireNonNull(currentUserAccessor, "currentUserAccessor");
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<PageResponse<MyRoomListItem>> listMyRooms(
		@Valid @ModelAttribute
		MyRoomListRequest listRequest,
		HttpServletRequest servletRequest) {
		RoomQueryParameterValidator.validateMyRoomList(servletRequest);
		return ApiResponse.success(
			HttpStatus.OK,
			myRoomQueryService.findPage(
				currentUserAccessor.requireCurrentUserId(),
				listRequest.getRole(),
				listRequest.getPage(),
				listRequest.getSize()));
	}
}
