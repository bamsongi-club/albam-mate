package cloud.bamsongi.albammate.notification.dto;

/** 현재 인증 사용자의 미확인·미만료 알림 수다. */
public record UnreadNotificationCountResponse(long unreadCount) {
}
