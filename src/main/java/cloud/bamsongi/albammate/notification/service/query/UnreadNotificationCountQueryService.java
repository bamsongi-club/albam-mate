package cloud.bamsongi.albammate.notification.service.query;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.notification.dto.UnreadNotificationCountResponse;
import cloud.bamsongi.albammate.notification.repository.NotificationQueryRepository;

/** 미확인 개수를 독립된 짧은 PostgreSQL 읽기 트랜잭션에서 조회한다. */
@Service
public class UnreadNotificationCountQueryService {

	private final NotificationQueryRepository notificationQueryRepository;

	public UnreadNotificationCountQueryService(NotificationQueryRepository notificationQueryRepository) {
		this.notificationQueryRepository = Objects.requireNonNull(notificationQueryRepository,
			"notificationQueryRepository");
	}

	@Transactional(readOnly = true)
	public UnreadNotificationCountResponse countUnread(long recipientUserId) {
		return new UnreadNotificationCountResponse(
			notificationQueryRepository.countUnreadUnexpired(recipientUserId));
	}
}
