package cloud.bamsongi.albammate.notification.dto;

import java.time.Instant;

/** 하나의 PostgreSQL 문장 스냅샷으로 고정한 일괄 읽음 결과다. */
public record NotificationBulkReadResponse(long updatedCount, Long boundaryNotificationId, Instant readAt) {
}
