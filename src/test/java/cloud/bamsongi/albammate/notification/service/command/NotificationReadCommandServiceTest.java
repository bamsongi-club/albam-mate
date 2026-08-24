package cloud.bamsongi.albammate.notification.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.notification.dto.NotificationBulkReadResponse;
import cloud.bamsongi.albammate.notification.dto.NotificationListItem;
import cloud.bamsongi.albammate.notification.enums.NotificationType;
import cloud.bamsongi.albammate.notification.repository.NotificationReadRepository;

class NotificationReadCommandServiceTest {

	private final NotificationReadRepository notificationReadRepository = Mockito
		.mock(NotificationReadRepository.class);
	private final NotificationReadCommandService notificationReadCommandService = new NotificationReadCommandService(
		notificationReadRepository);

	@Test
	void 단건_읽음은_Repository가_반환한_현재_알림을_그대로_반환한다() {
		NotificationListItem expected = new NotificationListItem(3L, NotificationType.ROOM_CANCELED, 7L, "현재 방 제목",
			Instant.parse("2026-08-03T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"));
		when(notificationReadRepository.markReadAndFindCurrentItem(42L, 3L)).thenReturn(Optional.of(expected));

		assertEquals(expected, notificationReadCommandService.readOne(42L, 3L));
		verify(notificationReadRepository).markReadAndFindCurrentItem(42L, 3L);
	}

	@Test
	void 단건_읽음에서_미존재_타인_만료를_하나의_NOT_FOUND로_숨긴다() {
		when(notificationReadRepository.markReadAndFindCurrentItem(42L, 3L)).thenReturn(Optional.empty());

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> notificationReadCommandService.readOne(42L, 3L));

		assertEquals(ErrorCode.NOTIFICATION_NOT_FOUND, exception.getErrorCode());
	}

	@Test
	void 일괄_읽음은_Repository의_문장_스냅샷_결과를_그대로_반환한다() {
		NotificationBulkReadResponse expected = new NotificationBulkReadResponse(
			0, null, Instant.parse("2026-08-03T00:00:00Z"));
		when(notificationReadRepository.markAllUnreadAsRead(42L)).thenReturn(expected);

		assertEquals(expected, notificationReadCommandService.readAll(42L));
		verify(notificationReadRepository).markAllUnreadAsRead(42L);
	}
}
