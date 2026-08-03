package cloud.bamsongi.albammate.notification.service.query;

import java.util.Objects;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.notification.dto.NotificationListItem;
import cloud.bamsongi.albammate.notification.repository.NotificationQueryRepository;

/** 목록 본문과 count를 하나의 짧은 PostgreSQL 읽기 트랜잭션에서 조회한다. */
@Service
public class NotificationListQueryService {

	private final NotificationQueryRepository notificationQueryRepository;

	public NotificationListQueryService(NotificationQueryRepository notificationQueryRepository) {
		this.notificationQueryRepository = Objects.requireNonNull(notificationQueryRepository,
			"notificationQueryRepository");
	}

	@Transactional(readOnly = true)
	public PageResponse<NotificationListItem> findPage(long recipientUserId, int page, int size) {
		PageRequest pageable = PageRequest.of(page, size);
		return PageResponse.from(new PageImpl<>(
			notificationQueryRepository.findPage(recipientUserId, page, size),
			pageable,
			notificationQueryRepository.countUnexpired(recipientUserId)));
	}
}
