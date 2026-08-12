package cloud.bamsongi.albammate.notification.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.measurement.AuthNotificationMeasurementRecorder;
import cloud.bamsongi.albammate.notification.dto.NotificationListItem;
import cloud.bamsongi.albammate.notification.enums.NotificationType;
import cloud.bamsongi.albammate.notification.repository.NotificationQueryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class NotificationQueryServiceTest {

	@Test
	void 본인_조회결과와_같은_저장소_count로_페이지를_조립한다() {
		NotificationQueryRepository repository = mock(NotificationQueryRepository.class);
		when(repository.findPage(7L, 1, 10)).thenReturn(List.of(new NotificationListItem(2L,
			NotificationType.ROOM_CANCELED, 9L, "현재 제목", null, Instant.EPOCH)));
		when(repository.countUnexpired(7L)).thenReturn(11L);

		var result = new NotificationQueryService(repository).findPage(7L, 1, 10);

		assertEquals(11, result.totalElements());
		assertEquals(2, result.content().getFirst().id());
		verify(repository).findPage(7L, 1, 10);
		verify(repository).countUnexpired(7L);
	}

	@Test
	void 본인의_미확인_수_0도_그대로_반환한다() {
		NotificationQueryRepository repository = mock(NotificationQueryRepository.class);
		when(repository.countUnreadUnexpired(7L)).thenReturn(0L);

		assertEquals(0, new NotificationQueryService(repository).countUnread(7L).unreadCount());
		verify(repository).countUnreadUnexpired(7L);
	}

	@Test
	void T7_목록_content_total_count와_별도_unread_count를_요청별로_분리_기록한다() {
		NotificationQueryRepository repository = mock(NotificationQueryRepository.class);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		when(repository.findPage(7L, 0, 10)).thenReturn(List.of());
		when(repository.countUnexpired(7L)).thenReturn(0L);
		when(repository.countUnreadUnexpired(7L)).thenReturn(0L);
		NotificationQueryService service = new NotificationQueryService(repository,
			new AuthNotificationMeasurementRecorder(registry));

		service.findPage(7L, 0, 10);
		service.countUnread(7L);

		assertEquals(1, registry.find("notification.query.stage.duration").tag("stage", "content").timer().count());
		assertEquals(1, registry.find("notification.query.stage.duration").tag("stage", "total-count").timer().count());
		assertEquals(1,
			registry.find("notification.query.stage.duration").tag("stage", "unread-count").timer().count());
	}
}
