package cloud.bamsongi.albammate.notification.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxEventType;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxStatus;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxRecipientRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationRepository;

class NotificationRelayExecutorTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-08-03T00:00:00Z");
	private static final Instant RECORDED_AT = Instant.parse("2026-08-03T00:01:00Z");
	private static final Instant OPERATION_TIME = Instant.parse("2026-08-03T00:02:00Z");

	@Test
	void 수신자_스냅샷으로_누락_알림을_멱등_저장하고_같은_시각으로_처리_완료한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		NotificationOutboxEvent event = pendingEvent(10L);
		NotificationOutboxEventRepository.RelayClaim claim = claim(10L);
		when(eventRepository.claimEarliestProcessableEvent()).thenReturn(Optional.of(claim));
		when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
		when(recipientRepository.findRecipientUserIdsByOutboxEventId(10L)).thenReturn(List.of(2L, 3L));
		NotificationRelayExecutor executor = new NotificationRelayExecutor(
			eventRepository, recipientRepository, notificationRepository, new NotificationEventTypeMapper());

		NotificationRelayExecutor.ProcessedEvent processedEvent = executor.processOne().orElseThrow();

		verify(notificationRepository).insertIfAbsent(10L, 2L, 5L, "PARTICIPANT_JOINED", OCCURRED_AT, OPERATION_TIME,
			OCCURRED_AT.plusSeconds(90L * 24 * 60 * 60));
		verify(notificationRepository).insertIfAbsent(10L, 3L, 5L, "PARTICIPANT_JOINED", OCCURRED_AT, OPERATION_TIME,
			OCCURRED_AT.plusSeconds(90L * 24 * 60 * 60));
		assertEquals(NotificationOutboxStatus.PROCESSED, event.getStatus());
		assertEquals(OPERATION_TIME, event.getProcessedAt());
		assertEquals(OPERATION_TIME.plusSeconds(30L * 24 * 60 * 60), event.getCleanupAt());
		assertEquals(2, processedEvent.recipientCount());
		assertEquals(OPERATION_TIME, processedEvent.notificationRecordedAt());
	}

	@Test
	void Notification_저장이_실패하면_처리_완료로_전환하지_않고_원래_예외를_전달한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		NotificationOutboxEvent event = pendingEvent(10L);
		NotificationOutboxEventRepository.RelayClaim claim = claim(10L);
		when(eventRepository.claimEarliestProcessableEvent()).thenReturn(Optional.of(claim));
		when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
		when(recipientRepository.findRecipientUserIdsByOutboxEventId(10L)).thenReturn(List.of(2L));
		when(notificationRepository.insertIfAbsent(anyLong(), anyLong(), anyLong(), any(), any(), any(), any()))
			.thenThrow(new DataIntegrityViolationException("insert failed"));
		NotificationRelayExecutor executor = new NotificationRelayExecutor(
			eventRepository, recipientRepository, notificationRepository, new NotificationEventTypeMapper());

		assertThrows(DataIntegrityViolationException.class, executor::processOne);

		assertEquals(NotificationOutboxStatus.PENDING, event.getStatus());
	}

	@Test
	void 빈_수신자_스냅샷은_처리_완료로_전환하지_않고_상위_실패_경계로_전달한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		NotificationOutboxEvent event = pendingEvent(10L);
		NotificationOutboxEventRepository.RelayClaim claim = claim(10L);
		when(eventRepository.claimEarliestProcessableEvent()).thenReturn(Optional.of(claim));
		when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
		when(recipientRepository.findRecipientUserIdsByOutboxEventId(10L)).thenReturn(List.of());
		NotificationRelayExecutor executor = new NotificationRelayExecutor(
			eventRepository, recipientRepository, notificationRepository, new NotificationEventTypeMapper());

		assertThrows(IllegalStateException.class, executor::processOne);

		verifyNoInteractions(notificationRepository);
		assertEquals(NotificationOutboxStatus.PENDING, event.getStatus());
	}

	@Test
	void 정상_이벤트_구조화_로그는_필수_필드만_남기고_민감_값을_노출하지_않는다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		NotificationOutboxEvent event = pendingEvent(10L);
		NotificationOutboxEventRepository.RelayClaim claim = claim(10L);
		when(eventRepository.claimEarliestProcessableEvent()).thenReturn(Optional.of(claim));
		when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
		when(recipientRepository.findRecipientUserIdsByOutboxEventId(10L)).thenReturn(List.of(987_654_321L));
		NotificationRelayExecutor executor = new NotificationRelayExecutor(
			eventRepository, recipientRepository, notificationRepository, new NotificationEventTypeMapper());
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			executor.processOne();

			assertEquals(1, appender.list.size());
			assertEquals(Level.INFO, appender.list.getFirst().getLevel());
			String message = appender.list.getFirst().getFormattedMessage();
			assertTrue(message.contains("event=notification_outbox_relay_event_processed sourceEventId=10"));
			assertTrue(message.contains("eventType=PARTICIPATION_JOINED recipientCount=1"));
			assertTrue(message.contains("outboxRecordedAt=" + RECORDED_AT));
			assertTrue(message.contains("notificationRecordedAt=" + OPERATION_TIME));
			assertTrue(message.contains("failureCount=0 totalFailureCount=0 reprocessCount=0"));
			assertTrue(message.contains("deliveryDelayMs=60000 processingDurationMs="));
			assertNoSensitiveValue(message);
		} finally {
			detachLogAppender(appender);
		}
	}

	private static NotificationOutboxEvent pendingEvent(Long eventId) {
		NotificationOutboxEvent event = NotificationOutboxEvent.createPending(
			NotificationOutboxEventType.PARTICIPATION_JOINED, 5L, OCCURRED_AT, RECORDED_AT);
		ReflectionTestUtils.setField(event, "id", eventId);
		return event;
	}

	private static NotificationOutboxEventRepository.RelayClaim claim(Long eventId) {
		NotificationOutboxEventRepository.RelayClaim claim = mock(NotificationOutboxEventRepository.RelayClaim.class);
		when(claim.getId()).thenReturn(eventId);
		when(claim.getAvailableAt()).thenReturn(RECORDED_AT);
		when(claim.getOperationTime()).thenReturn(OPERATION_TIME);
		return claim;
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationRelayExecutor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationRelayExecutor.class);
		logger.detachAppender(appender);
		appender.stop();
	}

	private void assertNoSensitiveValue(String message) {
		assertFalse(message.contains("987654321"));
		assertFalse(message.contains("relay-room-title-sensitive"));
		assertFalse(message.contains("relay-payload-sensitive"));
		assertFalse(message.contains("select * from notifications"));
		assertFalse(message.contains("relay-session-sensitive"));
	}
}
