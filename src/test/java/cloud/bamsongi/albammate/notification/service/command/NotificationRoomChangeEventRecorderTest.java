package cloud.bamsongi.albammate.notification.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;
import cloud.bamsongi.albammate.notification.entity.NotificationOutboxRecipient;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxEventType;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxRecipientRepository;
import cloud.bamsongi.albammate.room.contract.ParticipationCanceledEvent;
import cloud.bamsongi.albammate.room.contract.ParticipationJoinedEvent;
import cloud.bamsongi.albammate.room.contract.RoomCanceledEvent;

@ExtendWith(MockitoExtension.class)
class NotificationRoomChangeEventRecorderTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-08-06T00:00:00Z");
	private static final Instant RECORDED_AT = Instant.parse("2026-08-06T00:00:01Z");

	@Mock
	private NotificationOutboxEventRepository eventRepository;
	@Mock
	private NotificationOutboxRecipientRepository recipientRepository;
	@Mock
	private NotificationOutboxEvent savedEvent;

	@Test
	void 세_이벤트를_각각의_Outbox_enum과_분리된_수신자_스냅샷으로_저장한다() {
		when(eventRepository.findPostgresOperationTime()).thenReturn(RECORDED_AT);
		when(eventRepository.saveAndFlush(any(NotificationOutboxEvent.class))).thenReturn(savedEvent);
		when(savedEvent.getId()).thenReturn(91L);
		NotificationRoomChangeEventRecorder recorder = new NotificationRoomChangeEventRecorder(
			eventRepository,
			recipientRepository);

		recorder.record(new ParticipationJoinedEvent(7L, OCCURRED_AT), List.of(11L));
		recorder.record(new ParticipationCanceledEvent(7L, OCCURRED_AT), List.of(12L));
		recorder.record(new RoomCanceledEvent(7L, OCCURRED_AT), List.of(11L, 12L));

		verify(eventRepository, org.mockito.Mockito.times(3)).findPostgresOperationTime();
		ArgumentCaptor<NotificationOutboxEvent> eventCaptor = ArgumentCaptor.forClass(NotificationOutboxEvent.class);
		verify(eventRepository, org.mockito.Mockito.times(3)).saveAndFlush(eventCaptor.capture());
		assertEquals(
			List.of(
				NotificationOutboxEventType.PARTICIPATION_JOINED,
				NotificationOutboxEventType.PARTICIPATION_CANCELED,
				NotificationOutboxEventType.ROOM_CANCELED),
			eventCaptor.getAllValues().stream().map(NotificationOutboxEvent::getEventType).toList());
		assertEquals(7L, eventCaptor.getAllValues().get(2).getRoomId());
		assertEquals(OCCURRED_AT, eventCaptor.getAllValues().get(2).getOccurredAt());
		assertEquals(RECORDED_AT, eventCaptor.getAllValues().get(2).getRecordedAt());
		ArgumentCaptor<List<NotificationOutboxRecipient>> recipientsCaptor = ArgumentCaptor.forClass(List.class);
		verify(recipientRepository, org.mockito.Mockito.times(3)).saveAll(recipientsCaptor.capture());
		assertEquals(
			List.of(List.of(11L), List.of(12L), List.of(11L, 12L)),
			recipientsCaptor.getAllValues().stream()
				.map(recipients -> recipients.stream()
					.map(recipient -> recipient.getId().getRecipientUserId())
					.toList())
				.toList());
	}
}
