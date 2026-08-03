package cloud.bamsongi.albammate.notification.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class NotificationRelaySchedulerTest {

	@Test
	void 활성화된_relay는_batch_처리를_시작한다() {
		NotificationRelayCoordinator coordinator = mock(NotificationRelayCoordinator.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		NotificationRelayScheduler scheduler = new NotificationRelayScheduler(coordinator, fixedClock(), properties);

		scheduler.relayProcessableEvents();

		verify(coordinator).processBatch();
	}

	@Test
	void 비활성화된_relay는_저장_상태를_바꾸지_않는다() {
		NotificationRelayCoordinator coordinator = mock(NotificationRelayCoordinator.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		properties.setEnabled(false);
		NotificationRelayScheduler scheduler = new NotificationRelayScheduler(coordinator, fixedClock(), properties);

		scheduler.relayProcessableEvents();

		verifyNoInteractions(coordinator);
	}

	@Test
	void scheduler_실패는_이벤트별_실패_기록으로_전달하지_않는다() {
		NotificationRelayCoordinator coordinator = mock(NotificationRelayCoordinator.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		org.mockito.Mockito.doThrow(new IllegalStateException("scheduler database failure"))
			.when(coordinator).processBatch();
		NotificationRelayScheduler scheduler = new NotificationRelayScheduler(coordinator, fixedClock(), properties);

		scheduler.relayProcessableEvents();

		verify(coordinator).processBatch();
	}

	@Test
	void scheduler_실패_로그는_주입한_Clock의_시각을_사용한다() {
		NotificationRelayCoordinator coordinator = mock(NotificationRelayCoordinator.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		org.mockito.Mockito.doThrow(new IllegalStateException("scheduler database failure"))
			.when(coordinator).processBatch();
		NotificationRelayScheduler scheduler = new NotificationRelayScheduler(coordinator, fixedClock(), properties);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			scheduler.relayProcessableEvents();

			assertEquals(1, appender.list.size());
			assertEquals("event=notification_outbox_relay_scheduler_failed failureCode=RELAY_SCHEDULER_FAILURE "
				+ "exceptionClass=IllegalStateException occurredAt=2026-08-03T00:00:00Z",
				appender.list.getFirst().getFormattedMessage());
		} finally {
			detachLogAppender(appender);
		}
	}

	private Clock fixedClock() {
		return Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationRelayScheduler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationRelayScheduler.class);
		logger.detachAppender(appender);
		appender.stop();
	}
}
