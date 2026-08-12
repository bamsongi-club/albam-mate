package cloud.bamsongi.albammate.notification.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.measurement.AuthNotificationMeasurementRecorder;
import cloud.bamsongi.albammate.notification.entity.Notification;
import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxEventType;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxStatus;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxRecipientRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class NotificationRelayExecutorTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-08-03T00:00:00Z");
	private static final Instant RECORDED_AT = Instant.parse("2026-08-03T00:01:00Z");
	private static final Instant OPERATION_TIME = Instant.parse("2026-08-03T00:02:00Z");

	@BeforeEach
	void initializeTransactionSynchronization() {
		TransactionSynchronizationManager.initSynchronization();
	}

	@AfterEach
	void clearTransactionSynchronization() {
		TransactionSynchronizationManager.clearSynchronization();
	}

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
			eventRepository, recipientRepository, notificationRepository, null);

		NotificationRelayExecutor.ProcessedEvent processedEvent = executor.processOne().orElseThrow();

		ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository, times(2)).insertIfAbsent(notificationCaptor.capture());
		assertStoredNotification(notificationCaptor.getAllValues().get(0), 2L);
		assertStoredNotification(notificationCaptor.getAllValues().get(1), 3L);
		assertEquals(NotificationOutboxStatus.PROCESSED, event.getStatus());
		assertEquals(OPERATION_TIME, event.getProcessedAt());
		assertEquals(OPERATION_TIME.plusSeconds(30L * 24 * 60 * 60), event.getCleanupAt());
		assertEquals(2, processedEvent.recipientCount());
		assertEquals(OPERATION_TIME, processedEvent.notificationRecordedAt());
	}

	@Test
	void Notification_저장이_실패하면_처리_완료로_전환하지_않고_선점한_이벤트_ID와_함께_전달한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		NotificationOutboxEvent event = pendingEvent(10L);
		NotificationOutboxEventRepository.RelayClaim claim = claim(10L);
		when(eventRepository.claimEarliestProcessableEvent()).thenReturn(Optional.of(claim));
		when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
		when(recipientRepository.findRecipientUserIdsByOutboxEventId(10L)).thenReturn(List.of(2L));
		when(notificationRepository.insertIfAbsent(any(Notification.class)))
			.thenThrow(new DataIntegrityViolationException("insert failed"));
		NotificationRelayExecutor executor = new NotificationRelayExecutor(
			eventRepository, recipientRepository, notificationRepository, null);

		NotificationRelayProcessingException exception = assertThrows(
			NotificationRelayProcessingException.class, executor::processOne);

		assertEquals(10L, exception.getSourceEventId());
		assertEquals(
			NotificationRelayProcessingException.FailureReason.PROCESSING_FAILURE, exception.getFailureReason());
		assertTrue(exception.getCause() instanceof DataIntegrityViolationException);
		assertEquals(NotificationOutboxStatus.PENDING, event.getStatus());
	}

	@Test
	void T8_성공_relay는_저장_단계와_commit_afterCompletion을_분리_기록한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		NotificationOutboxEvent event = pendingEvent(10L);
		NotificationOutboxEventRepository.RelayClaim relayClaim = claim(10L);
		when(eventRepository.claimEarliestProcessableEvent()).thenReturn(Optional.of(relayClaim));
		when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
		when(recipientRepository.findRecipientUserIdsByOutboxEventId(10L)).thenReturn(List.of(2L));
		NotificationRelayExecutor executor = new NotificationRelayExecutor(eventRepository, recipientRepository,
			notificationRepository, new AuthNotificationMeasurementRecorder(registry));

		executor.processOne();
		TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.beforeCommit(false));
		TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
		TransactionSynchronizationManager.getSynchronizations()
			.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

		assertRelayTimer(registry, "claim", "success");
		assertRelayTimer(registry, "recipient-insert-loop", "success");
		assertRelayTimer(registry, "event-flush", "success");
		assertRelayTimer(registry, "tx-commit", "committed");
		assertRelayTimer(registry, "tx-total", "committed");
		assertRelayTimerHasPositiveDuration(registry, "tx-commit", "committed");
		assertRelayTimerHasPositiveDuration(registry, "tx-total", "committed");
		assertRelayTimer(registry, "afterCompletion", "committed");
	}

	@Test
	void T9_rollback_relay도_tx_total과_afterCompletion_결과를_정확히_기록한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		NotificationOutboxEvent event = pendingEvent(10L);
		NotificationOutboxEventRepository.RelayClaim relayClaim = claim(10L);
		when(eventRepository.claimEarliestProcessableEvent()).thenReturn(Optional.of(relayClaim));
		when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
		when(recipientRepository.findRecipientUserIdsByOutboxEventId(10L)).thenReturn(List.of(2L));
		when(notificationRepository.insertIfAbsent(any(Notification.class)))
			.thenThrow(new DataIntegrityViolationException("insert failed"));
		NotificationRelayExecutor executor = new NotificationRelayExecutor(eventRepository, recipientRepository,
			notificationRepository, new AuthNotificationMeasurementRecorder(registry));

		assertThrows(NotificationRelayProcessingException.class, executor::processOne);
		TransactionSynchronizationManager.getSynchronizations()
			.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

		assertRelayTimer(registry, "tx-total", "rolled-back");
		assertRelayTimerHasPositiveDuration(registry, "tx-total", "rolled-back");
		assertRelayTimer(registry, "afterCompletion", "rolled-back");
	}

	@Test
	void STATUS_UNKNOWN은_승인되지_않은_rollback_metric으로_오분류하지_않는다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		NotificationOutboxEvent event = pendingEvent(10L);
		NotificationOutboxEventRepository.RelayClaim relayClaim = claim(10L);
		when(eventRepository.claimEarliestProcessableEvent()).thenReturn(Optional.of(relayClaim));
		when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
		when(recipientRepository.findRecipientUserIdsByOutboxEventId(10L)).thenReturn(List.of(2L));
		NotificationRelayExecutor executor = new NotificationRelayExecutor(eventRepository, recipientRepository,
			notificationRepository, new AuthNotificationMeasurementRecorder(registry));

		executor.processOne();
		TransactionSynchronizationManager.getSynchronizations()
			.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN));

		assertEquals(0, registry.find("notification.relay.stage.duration")
			.tags("stage", "tx-total", "result", "rolled-back").timer().count());
		assertEquals(0, registry.find("notification.relay.stage.duration")
			.tags("stage", "afterCompletion", "result", "rolled-back").timer().count());
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
			eventRepository, recipientRepository, notificationRepository, null);

		NotificationRelayProcessingException exception = assertThrows(
			NotificationRelayProcessingException.class, executor::processOne);

		assertEquals(10L, exception.getSourceEventId());
		assertEquals(
			NotificationRelayProcessingException.FailureReason.MISSING_RECIPIENT_SNAPSHOT,
			exception.getFailureReason());
		verifyNoInteractions(notificationRepository);
		assertEquals(NotificationOutboxStatus.PENDING, event.getStatus());
	}

	@Test
	void 정상_이벤트_구조화_로그는_커밋_전에는_남기지_않고_afterCommit_뒤에_필수_필드만_남긴다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		NotificationOutboxEvent event = pendingEvent(10L);
		NotificationOutboxEventRepository.RelayClaim claim = claim(10L);
		when(eventRepository.claimEarliestProcessableEvent()).thenReturn(Optional.of(claim));
		when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
		when(recipientRepository.findRecipientUserIdsByOutboxEventId(10L)).thenReturn(List.of(987_654_321L));
		NotificationRelayExecutor executor = new NotificationRelayExecutor(
			eventRepository, recipientRepository, notificationRepository, null);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			executor.processOne();

			assertTrue(appender.list.isEmpty());
			invokeAfterCommit();

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

	@Test
	void 처리_가능한_이벤트가_없으면_다른_저장소를_호출하지_않는다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		when(eventRepository.claimEarliestProcessableEvent()).thenReturn(Optional.empty());
		NotificationRelayExecutor executor = new NotificationRelayExecutor(
			eventRepository, recipientRepository, notificationRepository, null);

		Optional<NotificationRelayExecutor.ProcessedEvent> processedEvent = executor.processOne();

		assertTrue(processedEvent.isEmpty());
		verifyNoInteractions(recipientRepository, notificationRepository);
	}

	@Test
	void 선점_시각에_이미_만료된_이벤트는_알림을_만들지_않고_전용_실패로_전달한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxRecipientRepository recipientRepository = mock(NotificationOutboxRecipientRepository.class);
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		NotificationOutboxEvent event = NotificationOutboxEvent.createPending(
			NotificationOutboxEventType.PARTICIPATION_JOINED, 5L, OPERATION_TIME.minusSeconds(90L * 24 * 60 * 60),
			RECORDED_AT);
		ReflectionTestUtils.setField(event, "id", 10L);
		NotificationOutboxEventRepository.RelayClaim relayClaim = claim(10L);
		when(eventRepository.claimEarliestProcessableEvent()).thenReturn(Optional.of(relayClaim));
		when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
		NotificationRelayExecutor executor = new NotificationRelayExecutor(
			eventRepository, recipientRepository, notificationRepository, null);

		NotificationRelayProcessingException exception = assertThrows(
			NotificationRelayProcessingException.class, executor::processOne);

		assertEquals(NotificationRelayProcessingException.FailureReason.EXPIRED, exception.getFailureReason());
		verifyNoInteractions(recipientRepository, notificationRepository);
	}

	private void invokeAfterCommit() {
		TransactionSynchronizationManager.getSynchronizations()
			.forEach(TransactionSynchronization::afterCommit);
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

	private static void assertStoredNotification(Notification notification, Long recipientUserId) {
		assertEquals(10L, notification.getSourceEventId());
		assertEquals(recipientUserId, notification.getRecipientUserId());
		assertEquals(5L, notification.getRoomId());
		assertEquals("PARTICIPANT_JOINED", notification.getType().name());
		assertEquals(OCCURRED_AT, notification.getCreatedAt());
		assertEquals(OPERATION_TIME, notification.getRecordedAt());
		assertEquals(OCCURRED_AT.plusSeconds(90L * 24 * 60 * 60), notification.getExpiresAt());
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

	private void assertRelayTimer(SimpleMeterRegistry registry, String stage, String result) {
		assertEquals(1, registry.find("notification.relay.stage.duration")
			.tags("stage", stage, "result", result).timer().count());
	}

	private void assertRelayTimerHasPositiveDuration(SimpleMeterRegistry registry, String stage, String result) {
		assertTrue(registry.find("notification.relay.stage.duration")
			.tags("stage", stage, "result", result).timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) > 0);
	}
}
