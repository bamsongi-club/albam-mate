package cloud.bamsongi.albammate.notification.service.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.notification.dto.NotificationBulkReadResponse;
import cloud.bamsongi.albammate.notification.dto.NotificationListItem;
import cloud.bamsongi.albammate.notification.repository.NotificationReadRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 로그인 사용자가 자신의 알림을 읽음으로 처리하는 유스케이스다. */
@Service
@RequiredArgsConstructor
public class NotificationReadCommandService {

	@NonNull private final NotificationReadRepository notificationReadRepository;

	@Transactional
	public NotificationListItem readOne(long recipientUserId, long notificationId) {
		return notificationReadRepository.markReadAndFindCurrentItem(recipientUserId, notificationId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
	}

	@Transactional
	public NotificationBulkReadResponse readAll(long recipientUserId) {
		return notificationReadRepository.markAllUnreadAsRead(recipientUserId);
	}
}
