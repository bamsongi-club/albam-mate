package cloud.bamsongi.albammate.notification.service.query;

import java.util.Objects;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.measurement.AuthNotificationMeasurementRecorder;
import cloud.bamsongi.albammate.notification.dto.NotificationListItem;
import cloud.bamsongi.albammate.notification.dto.UnreadNotificationCountResponse;
import cloud.bamsongi.albammate.notification.repository.NotificationQueryRepository;

/** 로그인 사용자의 알림 목록과 미확인 개수를 조회하는 유스케이스다. */
@Service
public class NotificationQueryService {

	private final NotificationQueryRepository notificationQueryRepository;
	private final AuthNotificationMeasurementRecorder measurementRecorder;

	public NotificationQueryService(
		NotificationQueryRepository notificationQueryRepository,
		@org.springframework.lang.Nullable AuthNotificationMeasurementRecorder measurementRecorder) {
		this.notificationQueryRepository = Objects.requireNonNull(notificationQueryRepository,
			"notificationQueryRepository");
		this.measurementRecorder = measurementRecorder;
	}

	/**
	 * 한 목록 요청의 content와 count를 하나의 짧은 읽기 트랜잭션에서 조회한다.
	 * 미확인 개수 요청과는 트랜잭션을 공유하지 않으며, 이 요청만의 PostgreSQL transaction_timestamp()와
	 * DB 스냅샷을 사용한다.
	 */
	@Transactional(readOnly = true)
	public PageResponse<NotificationListItem> findPage(long recipientUserId, int page, int size) {
		PageRequest pageable = PageRequest.of(page, size);
		return PageResponse.from(new PageImpl<>(
			measure("content", () -> notificationQueryRepository.findPage(recipientUserId, page, size)),
			pageable,
			measure("total-count", () -> notificationQueryRepository.countUnexpired(recipientUserId))));
	}

	/**
	 * 한 미확인 개수 요청을 독립된 짧은 읽기 트랜잭션에서 조회한다.
	 * 목록 요청과는 트랜잭션을 공유하지 않으며, 이 요청만의 PostgreSQL transaction_timestamp()와
	 * DB 스냅샷을 사용한다.
	 */
	@Transactional(readOnly = true)
	public UnreadNotificationCountResponse countUnread(long recipientUserId) {
		return new UnreadNotificationCountResponse(
			measure("unread-count", () -> notificationQueryRepository.countUnreadUnexpired(recipientUserId)));
	}

	private <T> T measure(String stage, java.util.function.Supplier<T> work) {
		return measurementRecorder == null ? work.get() : measurementRecorder.queryStage(stage, work);
	}
}
