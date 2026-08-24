package cloud.bamsongi.albammate.notification.cleanup;

import static cloud.bamsongi.albammate.fixture.StructuredLogAssertions.fields;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class NotificationCleanupCoordinatorTest {

	private static final Instant MEASUREMENT_TIME = Instant.parse("2026-08-04T00:00:00Z");

	@Test
	void target별_batch_크기와_최대_batch_수를_넘지_않는다() {
		NotificationCleanupExecutor executor = mock(NotificationCleanupExecutor.class);
		NotificationCleanupProperties properties = properties(2, 2);
		when(executor.cleanupOneBatch(NotificationCleanupTarget.NOTIFICATION, 2))
			.thenReturn(batch(NotificationCleanupTarget.NOTIFICATION, 2))
			.thenReturn(batch(NotificationCleanupTarget.NOTIFICATION, 2));
		when(executor.cleanupOneBatch(NotificationCleanupTarget.OUTBOX, 2))
			.thenReturn(batch(NotificationCleanupTarget.OUTBOX, 1));
		NotificationCleanupCoordinator coordinator = new NotificationCleanupCoordinator(executor, properties);

		coordinator.cleanupExpiredData();

		verify(executor, times(2)).cleanupOneBatch(NotificationCleanupTarget.NOTIFICATION, 2);
		verify(executor).cleanupOneBatch(NotificationCleanupTarget.OUTBOX, 2);
	}

	@Test
	void 실패한_batch는_같은_실행에서_재시도하지_않고_다른_target은_계속_처리한다() {
		NotificationCleanupExecutor executor = mock(NotificationCleanupExecutor.class);
		NotificationCleanupProperties properties = properties(2, 5);
		doThrow(new IllegalStateException("cleanup failure with sensitive payload"))
			.when(executor).cleanupOneBatch(NotificationCleanupTarget.NOTIFICATION, 2);
		when(executor.cleanupOneBatch(NotificationCleanupTarget.OUTBOX, 2))
			.thenReturn(batch(NotificationCleanupTarget.OUTBOX, 0));
		NotificationCleanupCoordinator coordinator = new NotificationCleanupCoordinator(executor, properties);

		coordinator.cleanupExpiredData();

		verify(executor).cleanupOneBatch(NotificationCleanupTarget.NOTIFICATION, 2);
		verify(executor).cleanupOneBatch(NotificationCleanupTarget.OUTBOX, 2);
	}

	@Test
	void DB_시각_조회_전_실패_로그는_measurementTime을_생략하고_원본_예외_메시지를_노출하지_않는다() {
		NotificationCleanupExecutor executor = mock(NotificationCleanupExecutor.class);
		NotificationCleanupProperties properties = properties(2, 1);
		when(executor.cleanupOneBatch(eq(NotificationCleanupTarget.NOTIFICATION), eq(2)))
			.thenReturn(batch(NotificationCleanupTarget.NOTIFICATION, 1));
		doThrow(new IllegalStateException("email=user@example.com session=sensitive-token"))
			.when(executor).cleanupOneBatch(NotificationCleanupTarget.OUTBOX, 2);
		NotificationCleanupCoordinator coordinator = new NotificationCleanupCoordinator(executor, properties);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			coordinator.cleanupExpiredData();

			assertEquals(2, appender.list.size());
			assertEquals(Level.INFO, appender.list.get(0).getLevel());
			Map<String, Object> completedLog = fields(appender.list.get(0));
			assertEquals("notification_cleanup_completed", completedLog.get("event"));
			assertEquals(NotificationCleanupTarget.NOTIFICATION, completedLog.get("targetType"));
			assertEquals(MEASUREMENT_TIME, completedLog.get("measurementTime"));
			Map<String, Object> failureLog = fields(appender.list.get(1));
			assertEquals(Level.WARN, appender.list.get(1).getLevel());
			assertEquals("notification_cleanup_failed", failureLog.get("event"));
			assertEquals(
				Set.of("event", "targetType", "batchNumber", "deletedCount", "failureCode", "exceptionClass",
					"durationMs"),
				failureLog.keySet());
			assertEquals(NotificationCleanupTarget.OUTBOX, failureLog.get("targetType"));
			assertEquals("CLEANUP_BATCH_FAILURE", failureLog.get("failureCode"));
			assertEquals("IllegalStateException", failureLog.get("exceptionClass"));
			assertFalse(failureLog.containsKey("measurementTime"));
			assertNoSensitiveData(appender.list.get(1));
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void DB_시각_조회_뒤_실패_로그는_같은_measurementTime과_원_예외_클래스만_남긴다() {
		NotificationCleanupExecutor executor = mock(NotificationCleanupExecutor.class);
		NotificationCleanupProperties properties = properties(2, 1);
		doThrow(NotificationCleanupExecutor.CleanupBatchFailedException.afterMeasurement(
			MEASUREMENT_TIME,
			new IllegalArgumentException("email=user@example.com session=sensitive-token")))
			.when(executor).cleanupOneBatch(NotificationCleanupTarget.NOTIFICATION, 2);
		when(executor.cleanupOneBatch(NotificationCleanupTarget.OUTBOX, 2))
			.thenReturn(batch(NotificationCleanupTarget.OUTBOX, 0));
		NotificationCleanupCoordinator coordinator = new NotificationCleanupCoordinator(executor, properties);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			coordinator.cleanupExpiredData();

			Map<String, Object> failureLog = fields(appender.list.get(0));
			assertEquals(Level.WARN, appender.list.get(0).getLevel());
			assertEquals("notification_cleanup_failed", failureLog.get("event"));
			assertEquals(
				Set.of(
					"event", "targetType", "batchNumber", "deletedCount", "failureCode", "exceptionClass", "durationMs",
					"measurementTime"),
				failureLog.keySet());
			assertEquals(NotificationCleanupTarget.NOTIFICATION, failureLog.get("targetType"));
			assertEquals("CLEANUP_BATCH_FAILURE", failureLog.get("failureCode"));
			assertEquals("IllegalArgumentException", failureLog.get("exceptionClass"));
			assertEquals(MEASUREMENT_TIME, failureLog.get("measurementTime"));
			assertNoSensitiveData(appender.list.get(0));
		} finally {
			detachLogAppender(appender);
		}
	}

	private NotificationCleanupProperties properties(int batchSize, int maxBatchesPerTarget) {
		NotificationCleanupProperties properties = new NotificationCleanupProperties();
		properties.setBatchSize(batchSize);
		properties.setMaxBatchesPerTarget(maxBatchesPerTarget);
		return properties;
	}

	private NotificationCleanupExecutor.CleanupBatchResult batch(
		NotificationCleanupTarget targetType,
		long deletedCount) {
		return new NotificationCleanupExecutor.CleanupBatchResult(targetType, MEASUREMENT_TIME, deletedCount);
	}

	private void assertNoSensitiveData(ILoggingEvent event) {
		String structuredFields = fields(event).entrySet().stream()
			.map(entry -> entry.getKey() + "=" + entry.getValue())
			.collect(java.util.stream.Collectors.joining(" "));
		assertFalse(event.getFormattedMessage().contains("user@example.com"));
		assertFalse(event.getFormattedMessage().contains("sensitive-token"));
		assertFalse(structuredFields.contains("user@example.com"));
		assertFalse(structuredFields.contains("sensitive-token"));
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationCleanupCoordinator.class);
		logger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationCleanupCoordinator.class);
		logger.detachAppender(appender);
		logger.setLevel(null);
		appender.stop();
	}
}
