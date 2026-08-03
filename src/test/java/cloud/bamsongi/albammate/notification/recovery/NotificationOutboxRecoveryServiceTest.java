package cloud.bamsongi.albammate.notification.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxEventType;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxStatus;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxRecipientRepository;

class NotificationOutboxRecoveryServiceTest {

	private static final Instant OPERATION_TIME = Instant.parse("2026-08-03T00:00:00Z");

	@Test
	void preview는_상태를_바꾸지_않고_ID를_오름차순으로_출력한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationOutboxRecoveryService service = new NotificationOutboxRecoveryService(eventRepository,
			recipientRepository);
		NotificationOutboxEvent first = failed(2L, OPERATION_TIME.minusSeconds(60));
		NotificationOutboxEvent second = failed(7L, OPERATION_TIME.minusSeconds(60));
		when(eventRepository.findRecoveryOperationTime()).thenReturn(OPERATION_TIME);
		when(eventRepository.findAllByIdInOrderById(List.of(2L, 7L))).thenReturn(List.of(first, second));
		when(recipientRepository.existsByIdOutboxEventId(2L)).thenReturn(true);
		when(recipientRepository.existsByIdOutboxEventId(7L)).thenReturn(true);

		NotificationOutboxRecoveryResult result = service.preview(inspect(List.of(7L, 2L)));

		assertEquals(List.of(2L, 7L), result.eventIds());
		assertEquals(2, result.eligibleCount());
		verify(eventRepository, never()).reprocessAll(anyCollection(), any(), anyString());
		verify(eventRepository, never()).discardAll(anyCollection(), any(), any(), anyString());
	}

	@Test
	void 중복_ID는_조회_전에_입력_오류로_거절한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationOutboxRecoveryService service = new NotificationOutboxRecoveryService(eventRepository,
			recipientRepository);

		assertThrows(NotificationOutboxRecoveryInputException.class, () -> service.preview(inspect(List.of(3L, 3L))));
		verify(eventRepository, never()).findAllByIdInOrderById(anyCollection());
	}

	@Test
	void 적격_재처리는_고정된_시각으로_새_주기를_시작한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationOutboxRecoveryService service = new NotificationOutboxRecoveryService(eventRepository,
			recipientRepository);
		NotificationOutboxEvent event = failed(3L, OPERATION_TIME.minusSeconds(60));
		when(eventRepository.findRecoveryOperationTime()).thenReturn(OPERATION_TIME);
		when(eventRepository.findAllByIdInOrderByIdForUpdate(List.of(3L))).thenReturn(List.of(event));
		when(recipientRepository.existsByIdOutboxEventId(3L)).thenReturn(true);
		when(eventRepository.reprocessAll(List.of(3L), OPERATION_TIME, "fixed incident")).thenReturn(1);

		NotificationOutboxRecoveryResult result = service.execute(reprocess(List.of(3L)));

		assertEquals(1, result.changedCount());
		assertTrue(result.items().isEmpty());
		verify(eventRepository).reprocessAll(List.of(3L), OPERATION_TIME, "fixed incident");
		InOrder repositoryCalls = inOrder(eventRepository);
		repositoryCalls.verify(eventRepository).findAllByIdInOrderByIdForUpdate(List.of(3L));
		repositoryCalls.verify(eventRepository).findRecoveryOperationTime();
	}

	@Test
	void 하나라도_만료면_재처리는_부분_성공을_남기지_않는다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationOutboxRecoveryService service = new NotificationOutboxRecoveryService(eventRepository,
			recipientRepository);
		NotificationOutboxEvent eligible = failed(3L, OPERATION_TIME.minusSeconds(60));
		NotificationOutboxEvent expired = failed(5L, OPERATION_TIME.minusSeconds(89L * 24 * 60 * 60));
		when(eventRepository.findRecoveryOperationTime()).thenReturn(OPERATION_TIME);
		when(eventRepository.findAllByIdInOrderByIdForUpdate(List.of(3L, 5L))).thenReturn(List.of(eligible, expired));

		assertThrows(NotificationOutboxRecoveryInputException.class, () -> service.execute(reprocess(List.of(3L, 5L))));
		verify(eventRepository, never()).reprocessAll(anyCollection(), any(), anyString());
	}

	private static NotificationOutboxRecoveryRequest inspect(List<Long> eventIds) {
		return new NotificationOutboxRecoveryRequest(NotificationRecoveryAction.INSPECT, eventIds, true, null, null,
			null, null);
	}

	private static NotificationOutboxRecoveryRequest reprocess(List<Long> eventIds) {
		return new NotificationOutboxRecoveryRequest(NotificationRecoveryAction.REPROCESS, eventIds, false,
			"INC-2026-267", "fixed incident", "ops-user", null);
	}

	private static NotificationOutboxEvent failed(long eventId, Instant occurredAt) {
		NotificationOutboxEvent event = NotificationOutboxEvent.createPending(
			NotificationOutboxEventType.PARTICIPATION_JOINED, 1L, occurredAt, occurredAt);
		ReflectionTestUtils.setField(event, "id", eventId);
		ReflectionTestUtils.setField(event, "status", NotificationOutboxStatus.FAILED);
		ReflectionTestUtils.setField(event, "failureCount", 5);
		ReflectionTestUtils.setField(event, "totalFailureCount", 5);
		ReflectionTestUtils.setField(event, "lastFailureCode", "RELAY_PROCESSING_FAILURE");
		ReflectionTestUtils.setField(event, "lastFailedAt", occurredAt);
		ReflectionTestUtils.setField(event, "lastFailureClass", "IllegalStateException");
		ReflectionTestUtils.setField(event, "lastFailureMessage", "safe failure");
		ReflectionTestUtils.setField(event, "availableAt", null);
		return event;
	}
}
