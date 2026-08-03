package cloud.bamsongi.albammate.notification.dto;

import java.time.Instant;

import cloud.bamsongi.albammate.notification.enums.NotificationType;

/** 현재 방 제목을 조회 시점에 투영한 본인 알림 목록 항목이다. */
public record NotificationListItem(
	Long id,
	NotificationType type,
	Long roomId,
	String roomTitle,
	Instant readAt,
	Instant createdAt) {
}
