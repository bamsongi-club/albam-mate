package cloud.bamsongi.albammate.notification.controller;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.notification.dto.NotificationListItem;
import cloud.bamsongi.albammate.notification.dto.NotificationListRequest;
import cloud.bamsongi.albammate.notification.dto.UnreadNotificationCountResponse;
import cloud.bamsongi.albammate.notification.service.query.NotificationListQueryService;
import cloud.bamsongi.albammate.notification.service.query.UnreadNotificationCountQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** 로그인 사용자의 알림 목록과 미확인 개수 HTTP 경계다. */
@RestController
@RequestMapping("/api/users/me/notifications")
public class NotificationController {

	private final NotificationListQueryService notificationListQueryService;
	private final UnreadNotificationCountQueryService unreadNotificationCountQueryService;
	private final CurrentUserAccessor currentUserAccessor;

	public NotificationController(
		NotificationListQueryService notificationListQueryService,
		UnreadNotificationCountQueryService unreadNotificationCountQueryService,
		CurrentUserAccessor currentUserAccessor) {
		this.notificationListQueryService = Objects.requireNonNull(notificationListQueryService,
			"notificationListQueryService");
		this.unreadNotificationCountQueryService = Objects.requireNonNull(
			unreadNotificationCountQueryService, "unreadNotificationCountQueryService");
		this.currentUserAccessor = Objects.requireNonNull(currentUserAccessor, "currentUserAccessor");
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<PageResponse<NotificationListItem>>> listNotifications(
		@Valid @ModelAttribute
		NotificationListRequest request,
		HttpServletRequest servletRequest) {
		NotificationQueryParameterAllowlistValidator.validateList(servletRequest);
		return ResponseEntity.ok(ApiResponse.success(
			HttpStatus.OK,
			notificationListQueryService.findPage(
				currentUserAccessor.requireCurrentUserId(), request.getPage(), request.getSize())));
	}

	@GetMapping(path = "/unread-count", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<UnreadNotificationCountResponse>> unreadCount(HttpServletRequest servletRequest) {
		NotificationQueryParameterAllowlistValidator.validateUnreadCount(servletRequest);
		return ResponseEntity.ok(ApiResponse.success(
			HttpStatus.OK,
			unreadNotificationCountQueryService.countUnread(currentUserAccessor.requireCurrentUserId())));
	}
}
