package cloud.bamsongi.albammate.notification.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.notification.repository.NotificationQueryRepository;

class UnreadNotificationCountQueryServiceTest {

	@Test
	void 본인의_미확인_수_0도_그대로_반환한다() {
		NotificationQueryRepository repository = mock(NotificationQueryRepository.class);
		when(repository.countUnreadUnexpired(7L)).thenReturn(0L);

		assertEquals(0, new UnreadNotificationCountQueryService(repository).countUnread(7L).unreadCount());
		verify(repository).countUnreadUnexpired(7L);
	}
}
