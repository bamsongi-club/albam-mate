package cloud.bamsongi.albammate.notification.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.notification.dto.NotificationListItem;
import cloud.bamsongi.albammate.notification.enums.NotificationType;
import cloud.bamsongi.albammate.notification.repository.NotificationQueryRepository;

class NotificationListQueryServiceTest {

	@Test
	void 본인_조회결과와_같은_저장소_count로_페이지를_조립한다() {
		NotificationQueryRepository repository = mock(NotificationQueryRepository.class);
		when(repository.findPage(7L, 1, 10)).thenReturn(List.of(new NotificationListItem(2L,
			NotificationType.ROOM_CANCELED, 9L, "현재 제목", null, Instant.EPOCH)));
		when(repository.countUnexpired(7L)).thenReturn(11L);

		var result = new NotificationListQueryService(repository).findPage(7L, 1, 10);

		assertEquals(11, result.totalElements());
		assertEquals(2, result.content().getFirst().id());
		verify(repository).findPage(7L, 1, 10);
		verify(repository).countUnexpired(7L);
	}
}
