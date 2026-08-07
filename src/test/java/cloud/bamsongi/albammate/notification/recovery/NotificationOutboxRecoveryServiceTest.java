package cloud.bamsongi.albammate.notification.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxEventType;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxStatus;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxRecipientRepository;

class NotificationOutboxRecoveryServiceTest {

	private static final Instant OPERATION_TIME = Instant.parse("2026-08-03T00:00:00Z");

	@Test
	void preview는_ID를_정렬하고_수신자_스냅샷을_한번에_조회해_최종_판정한다() {
		Fixture fixture = fixture();
		NotificationOutboxEvent first = failed(2L, OPERATION_TIME.minusSeconds(60));
		NotificationOutboxEvent second = failed(7L, OPERATION_TIME.minusSeconds(60));
		when(fixture.eventRepository().findPostgresOperationTime()).thenReturn(OPERATION_TIME);
		when(fixture.eventRepository().findAllByIdInOrderById(List.of(2L, 7L))).thenReturn(List.of(first, second));
		when(fixture.recipientRepository().findOutboxEventIdsWithRecipients(List.of(2L, 7L)))
			.thenReturn(List.of(2L, 7L));

		NotificationOutboxRecoveryResult result = fixture.service().preview(inspect(List.of(7L, 2L)));

		assertEquals(List.of(2L, 7L), result.eventIds());
		assertEquals(2, result.eligibleCount());
		assertTrue(result.items().stream().allMatch(NotificationOutboxRecoveryItem::reprocessable));
		verify(fixture.recipientRepository()).findOutboxEventIdsWithRecipients(List.of(2L, 7L));
	}

	@Test
	void preview_DISCARD는_batch_조회로_실제_reprocessable을_결과에_담는다() {
		Fixture fixture = fixture();
		NotificationOutboxEvent event = failed(3L, OPERATION_TIME.minusSeconds(60));
		when(fixture.eventRepository().findPostgresOperationTime()).thenReturn(OPERATION_TIME);
		when(fixture.eventRepository().findAllByIdInOrderById(List.of(3L))).thenReturn(List.of(event));
		when(fixture.recipientRepository().findOutboxEventIdsWithRecipients(List.of(3L))).thenReturn(List.of(3L));

		NotificationOutboxRecoveryResult result = fixture.service().preview(discardPreview(List.of(3L)));

		assertTrue(result.items().getFirst().reprocessable());
		assertTrue(result.items().getFirst().eligible());
		verify(fixture.recipientRepository()).findOutboxEventIdsWithRecipients(List.of(3L));
	}

	@Test
	void 입력오류는_Repository_접근_전에_차단한다() {
		Fixture fixture = fixture();

		assertThrows(NotificationOutboxRecoveryInputException.class,
			() -> fixture.service().preview(inspect(List.of(3L, 3L))));
		assertThrows(NotificationOutboxRecoveryInputException.class,
			() -> fixture.service().execute(new NotificationOutboxRecoveryRequest(
				NotificationRecoveryAction.REPROCESS, List.of(3L), false, "ISSUE-267", "reason",
				"ops-user\r\nforged", null)));

		verifyNoInteractions(fixture.eventRepository(), fixture.recipientRepository());
	}

	@Test
	void preview와_execute_REPROCESS는_같은_최종_판정을_쓰고_batch_조회를_각각_한번만_호출한다() {
		Fixture fixture = fixture();
		List<Long> eventIds = List.of(3L, 5L);
		List<NotificationOutboxEvent> events = List.of(
			failed(3L, OPERATION_TIME.minusSeconds(60)),
			failed(5L, OPERATION_TIME.minusSeconds(120)));
		when(fixture.eventRepository().findPostgresOperationTime()).thenReturn(OPERATION_TIME);
		when(fixture.eventRepository().findAllByIdInOrderById(eventIds)).thenReturn(events);
		when(fixture.eventRepository().findAllByIdInOrderByIdForUpdate(eventIds)).thenReturn(events);
		when(fixture.recipientRepository().findOutboxEventIdsWithRecipients(eventIds)).thenReturn(eventIds);
		when(fixture.eventRepository().reprocessAll(eventIds, OPERATION_TIME, "fixed incident")).thenReturn(2);

		NotificationOutboxRecoveryResult preview = fixture.service().preview(reprocessPreview(eventIds));
		NotificationOutboxRecoveryResult executed = fixture.service().execute(reprocess(eventIds));

		assertEquals(2, preview.eligibleCount());
		assertEquals(2, executed.changedCount());
		verify(fixture.recipientRepository(), times(2)).findOutboxEventIdsWithRecipients(eventIds);
	}

	@Test
	void 수신자_스냅샷이_없는_REPROCESS는_preview와_execute에서_같이_거절한다() {
		Fixture fixture = fixture();
		NotificationOutboxEvent event = failed(3L, OPERATION_TIME.minusSeconds(60));
		when(fixture.eventRepository().findPostgresOperationTime()).thenReturn(OPERATION_TIME);
		when(fixture.eventRepository().findAllByIdInOrderById(List.of(3L))).thenReturn(List.of(event));
		when(fixture.eventRepository().findAllByIdInOrderByIdForUpdate(List.of(3L))).thenReturn(List.of(event));
		when(fixture.recipientRepository().findOutboxEventIdsWithRecipients(List.of(3L))).thenReturn(List.of());

		assertEquals(0, fixture.service().preview(reprocessPreview(List.of(3L))).eligibleCount());
		assertThrows(NotificationOutboxRecoveryInputException.class,
			() -> fixture.service().execute(reprocess(List.of(3L))));

		verify(fixture.recipientRepository(), times(2)).findOutboxEventIdsWithRecipients(List.of(3L));
		verify(fixture.eventRepository(), never()).reprocessAll(anyCollection(), any(), anyString());
	}

	@Test
	void REPROCESS의_89일_경계_직전은_실행하고_정확한_경계와_이후는_거절한다() {
		assertEquals(1, executeReprocessAt(OPERATION_TIME.minus(Duration.ofDays(89)).plusSeconds(1)));
		assertEquals(0, executeReprocessAt(OPERATION_TIME.minus(Duration.ofDays(89))));
		assertEquals(0, executeReprocessAt(OPERATION_TIME.minus(Duration.ofDays(89)).minusSeconds(1)));
	}

	@Test
	void execute_DISCARD는_batch_조회없이_reprocessable을_소비하지_않고_eligible만_사용한다() {
		assertEquals(1, executeDiscardWithReprocessable(true));
		assertEquals(1, executeDiscardWithReprocessable(false));
	}

	@Test
	void 하나라도_부적격이면_변경과_수신자_삭제를_호출하지_않는다() {
		Fixture fixture = fixture();
		NotificationOutboxEvent eligible = failed(3L, OPERATION_TIME.minusSeconds(60));
		NotificationOutboxEvent ineligible = failed(5L, OPERATION_TIME.minusSeconds(60));
		ReflectionTestUtils.setField(ineligible, "status", NotificationOutboxStatus.RETRY_WAIT);
		when(fixture.eventRepository().findAllByIdInOrderByIdForUpdate(List.of(3L, 5L)))
			.thenReturn(List.of(eligible, ineligible));
		when(fixture.eventRepository().findPostgresOperationTime()).thenReturn(OPERATION_TIME);

		assertThrows(NotificationOutboxRecoveryInputException.class,
			() -> fixture.service().execute(discard(List.of(3L, 5L))));

		verify(fixture.recipientRepository(), never()).findOutboxEventIdsWithRecipients(anyCollection());
		verify(fixture.eventRepository(), never()).reprocessAll(anyCollection(), any(), anyString());
		verify(fixture.eventRepository(), never()).discardAll(anyCollection(), any(), any(), anyString());
		verify(fixture.recipientRepository(), never()).deleteByIdOutboxEventIdIn(any());
	}

	private static int executeReprocessAt(Instant occurredAt) {
		Fixture fixture = fixture();
		NotificationOutboxEvent event = failed(3L, occurredAt);
		when(fixture.eventRepository().findAllByIdInOrderByIdForUpdate(List.of(3L))).thenReturn(List.of(event));
		when(fixture.eventRepository().findPostgresOperationTime()).thenReturn(OPERATION_TIME);
		when(fixture.recipientRepository().findOutboxEventIdsWithRecipients(List.of(3L))).thenReturn(List.of(3L));
		when(fixture.eventRepository().reprocessAll(List.of(3L), OPERATION_TIME, "fixed incident")).thenReturn(1);
		try {
			return fixture.service().execute(reprocess(List.of(3L))).changedCount();
		} catch (NotificationOutboxRecoveryInputException exception) {
			verify(fixture.eventRepository(), never()).reprocessAll(anyCollection(), any(), anyString());
			return 0;
		}
	}

	private static int executeDiscardWithReprocessable(boolean reprocessable) {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationOutboxRecoveryPolicy policy = mock(NotificationOutboxRecoveryPolicy.class);
		NotificationOutboxRecoveryService service = new NotificationOutboxRecoveryService(eventRepository,
			recipientRepository, policy);
		NotificationOutboxRecoveryRequest request = discard(List.of(3L));
		when(policy.validateAndNormalize(request, NotificationOutboxRecoveryPolicy.ExecutionMode.EXECUTE))
			.thenReturn(List.of(3L));
		NotificationOutboxEvent event = failed(3L, OPERATION_TIME.minusSeconds(60));
		when(eventRepository.findAllByIdInOrderByIdForUpdate(List.of(3L))).thenReturn(List.of(event));
		when(eventRepository.findPostgresOperationTime()).thenReturn(OPERATION_TIME);
		when(policy.evaluateEligibility(event, NotificationRecoveryAction.DISCARD, OPERATION_TIME, false))
			.thenReturn(new NotificationOutboxRecoveryPolicy.RecoveryEligibility(reprocessable, true));
		when(eventRepository.discardAll(anyCollection(), any(), any(), anyString())).thenReturn(1);

		int changedCount = service.execute(request).changedCount();

		verify(recipientRepository, never()).findOutboxEventIdsWithRecipients(anyCollection());
		return changedCount;
	}

	private static Fixture fixture() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		return new Fixture(eventRepository, recipientRepository,
			new NotificationOutboxRecoveryService(eventRepository, recipientRepository,
				new NotificationOutboxRecoveryPolicy()));
	}

	private static NotificationOutboxRecoveryRequest inspect(List<Long> eventIds) {
		return new NotificationOutboxRecoveryRequest(NotificationRecoveryAction.INSPECT, eventIds, true, null, null,
			null, null);
	}

	private static NotificationOutboxRecoveryRequest reprocessPreview(List<Long> eventIds) {
		return new NotificationOutboxRecoveryRequest(NotificationRecoveryAction.REPROCESS, eventIds, true,
			"INC-2026-267", "fixed incident", "ops-user", null);
	}

	private static NotificationOutboxRecoveryRequest reprocess(List<Long> eventIds) {
		return new NotificationOutboxRecoveryRequest(NotificationRecoveryAction.REPROCESS, eventIds, false,
			"INC-2026-267", "fixed incident", "ops-user", null);
	}

	private static NotificationOutboxRecoveryRequest discard(List<Long> eventIds) {
		return new NotificationOutboxRecoveryRequest(NotificationRecoveryAction.DISCARD, eventIds, false,
			"INC-2026-267", "discard incident", "ops-user", "DISCARD");
	}

	private static NotificationOutboxRecoveryRequest discardPreview(List<Long> eventIds) {
		return new NotificationOutboxRecoveryRequest(NotificationRecoveryAction.DISCARD, eventIds, true,
			"INC-2026-267", "discard incident", "ops-user", null);
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

	private record Fixture(
		NotificationOutboxEventRepository eventRepository,
		NotificationOutboxRecipientRepository recipientRepository,
		NotificationOutboxRecoveryService service) {
	}
}
