package cloud.bamsongi.albammate.notification.recovery;

import java.util.List;

/** 운영 출력에 허용된 비민감 대상 요약이다. */
public record NotificationOutboxRecoveryResult(
	List<Long> eventIds,
	int eligibleCount,
	int changedCount,
	List<NotificationOutboxRecoveryItem> items) {
}
