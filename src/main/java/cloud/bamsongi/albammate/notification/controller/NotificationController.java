package cloud.bamsongi.albammate.notification.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.notification.dto.NotificationBulkReadResponse;
import cloud.bamsongi.albammate.notification.dto.NotificationListItem;
import cloud.bamsongi.albammate.notification.dto.NotificationListRequest;
import cloud.bamsongi.albammate.notification.dto.NotificationReadRequest;
import cloud.bamsongi.albammate.notification.dto.UnreadNotificationCountResponse;
import cloud.bamsongi.albammate.notification.service.command.NotificationReadCommandService;
import cloud.bamsongi.albammate.notification.service.query.NotificationQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 로그인 사용자의 알림 목록·미확인 개수·읽음 처리 HTTP 경계다. */
@RestController
@RequestMapping("/api/users/me/notifications")
@Validated
@RequiredArgsConstructor
public class NotificationController {

	@NonNull private final NotificationQueryService notificationQueryService;
	@NonNull private final NotificationReadCommandService notificationReadCommandService;
	@NonNull private final CurrentUserAccessor currentUserAccessor;

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<PageResponse<NotificationListItem>>> listNotifications(
		@Valid @ModelAttribute
		NotificationListRequest request,
		HttpServletRequest servletRequest) {
		NotificationQueryParameterAllowlistValidator.validateList(servletRequest);
		return ResponseEntity.ok(ApiResponse.success(
			HttpStatus.OK,
			notificationQueryService.findPage(
				currentUserAccessor.requireCurrentUserId(), request.getPage(), request.getSize())));
	}

	@GetMapping(path = "/unread-count", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<UnreadNotificationCountResponse>> unreadCount(HttpServletRequest servletRequest) {
		NotificationQueryParameterAllowlistValidator.validateUnreadCount(servletRequest);
		return ResponseEntity.ok(ApiResponse.success(
			HttpStatus.OK,
			notificationQueryService.countUnread(currentUserAccessor.requireCurrentUserId())));
	}

	@PatchMapping(path = "/{notificationId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<NotificationListItem>> readNotification(
		@PathVariable @Positive Long notificationId,
		@NotNull @Valid @RequestBody
		NotificationReadRequest request) {
		return ResponseEntity.ok(ApiResponse.success(
			HttpStatus.OK,
			notificationReadCommandService.readOne(currentUserAccessor.requireCurrentUserId(), notificationId)));
	}

	@PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<NotificationBulkReadResponse>> readAllNotifications(
		@NotNull @Valid @RequestBody
		NotificationReadRequest request) {
		return ResponseEntity.ok(ApiResponse.success(
			HttpStatus.OK,
			notificationReadCommandService.readAll(currentUserAccessor.requireCurrentUserId())));
	}
}
