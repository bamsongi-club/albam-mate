package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;

class RoomStatusCorrectionSchedulerTest {

	private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");
	private static final Instant TRIGGER_NOW = Instant.parse("2026-07-27T01:00:00Z");
	private static final long MAX_SCHEDULE_JITTER_MILLIS = RoomStatusCorrectionScheduler.MAX_SCHEDULE_JITTER
		.toMillis();

	@Test
	void 고정된_Clock의_현재_시각을_coordinator에_전달한다() {
		RoomStatusCorrectionCoordinator coordinator = mock(RoomStatusCorrectionCoordinator.class);
		RoomStatusCorrectionScheduler.JitterSource jitterSource = mock(
			RoomStatusCorrectionScheduler.JitterSource.class);
		RoomStatusCorrectionScheduler.Sleeper sleeper = mock(RoomStatusCorrectionScheduler.Sleeper.class);
		RoomStatusCorrectionScheduler scheduler = scheduler(coordinator, jitterSource, sleeper);

		scheduler.correctDueRooms();

		verify(coordinator).correctDueRooms(eq(NOW), any());
	}

	@Test
	void 변경된_방이_있으면_변경건수와_함께_INFO_완료_로그를_남긴다() {
		RoomStatusCorrectionCoordinator coordinator = mock(RoomStatusCorrectionCoordinator.class);
		RoomStatusCorrectionScheduler scheduler = scheduler(coordinator, maxInclusive -> 0L, delay -> {});
		when(coordinator.correctDueRooms(eq(NOW), any())).thenReturn(2);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			scheduler.correctDueRooms();

			assertEquals(1, appender.list.size());
			assertEquals(Level.INFO, appender.list.getFirst().getLevel());
			assertEquals("event=room_state_reconciliation_completed changedCount=2",
				appender.list.getFirst().getFormattedMessage());
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void 변경된_방이_없으면_DEBUG_완료_로그를_남긴다() {
		RoomStatusCorrectionCoordinator coordinator = mock(RoomStatusCorrectionCoordinator.class);
		RoomStatusCorrectionScheduler scheduler = scheduler(coordinator, maxInclusive -> 0L, delay -> {});
		when(coordinator.correctDueRooms(eq(NOW), any())).thenReturn(0);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			scheduler.correctDueRooms();

			assertEquals(1, appender.list.size());
			assertEquals(Level.DEBUG, appender.list.getFirst().getLevel());
			assertEquals("event=room_state_reconciliation_completed changedCount=0",
				appender.list.getFirst().getFormattedMessage());
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void 비정상_중단은_WARN을_한번_남기고_예외를_다시_던진다() {
		RoomStatusCorrectionCoordinator coordinator = mock(RoomStatusCorrectionCoordinator.class);
		RoomStatusCorrectionScheduler scheduler = scheduler(coordinator, maxInclusive -> 0L, delay -> {});
		IllegalStateException expected = new IllegalStateException("unexpected");
		doThrow(expected).when(coordinator).correctDueRooms(eq(NOW), any());
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			assertSame(expected, assertThrows(IllegalStateException.class, scheduler::correctDueRooms));

			assertEquals(1, appender.list.size());
			assertEquals(Level.WARN, appender.list.getFirst().getLevel());
			assertEquals("event=room_state_reconciliation_failed", appender.list.getFirst().getFormattedMessage());
			assertTrue(appender.list.getFirst().getThrowableProxy() == null);
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void 동시_변경_오류는_Coordinator_경고와_중복_기록하지_않는다() {
		RoomStatusCorrectionCoordinator coordinator = mock(RoomStatusCorrectionCoordinator.class);
		RoomStatusCorrectionScheduler scheduler = scheduler(coordinator, maxInclusive -> 0L, delay -> {});
		BusinessException expected = new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION);
		doThrow(expected).when(coordinator).correctDueRooms(eq(NOW), any());
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			assertSame(expected, assertThrows(BusinessException.class, scheduler::correctDueRooms));

			assertTrue(appender.list.isEmpty());
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void 정상과_예외_장기_실행은_ROOM_전용_slow_WARN을_한번_남기고_같거나_짧으면_남기지_않는다() {
		assertSlowWarningCount(null, Duration.ofSeconds(31), 1);
		assertSlowWarningCount(new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION), Duration.ofSeconds(31),
			1);
		assertSlowWarningCount(new IllegalStateException("unexpected"), Duration.ofSeconds(31), 1);
		assertSlowWarningCount(new IllegalStateException("unexpected"), Duration.ofSeconds(30), 0);
		assertSlowWarningCount(new IllegalStateException("unexpected"), Duration.ofSeconds(29), 0);
	}

	@Test
	void progress_점유가_경고_기준을_초과하면_ROOM_전용_WARN을_한번_기록한다() {
		RoomStatusCorrectionProgressStore progressStore = mock(RoomStatusCorrectionProgressStore.class);
		AtomicBoolean claimed = new AtomicBoolean();
		doAnswer(invocation -> {
			claimed.set(true);
			return null;
		}).when(progressStore).claimExecution(any());
		RoomStatusCorrectionScheduler scheduler = new RoomStatusCorrectionScheduler(
			lockAlwaysAcquired(), mock(RoomStatusCorrectionCoordinator.class), progressStore, schedulerProperties(),
			Clock.fixed(NOW, ZoneOffset.UTC)) {
			@Override
			long elapsedNanos() {
				return claimed.get() ? Duration.ofSeconds(30).plusNanos(1).toNanos() : 0L;
			}
		};
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			scheduler.correctDueRooms();

			assertEquals(1L, warningCount(appender, "event=room_status_correction_execution_slow"));
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void progress_점유_예외도_실패와_slow_WARN_경계에_포함한다() {
		RoomStatusCorrectionProgressStore progressStore = mock(RoomStatusCorrectionProgressStore.class);
		AtomicBoolean claimed = new AtomicBoolean();
		IllegalStateException expected = new IllegalStateException("progress claim failed");
		doAnswer(invocation -> {
			claimed.set(true);
			throw expected;
		}).when(progressStore).claimExecution(any());
		RoomStatusCorrectionScheduler scheduler = new RoomStatusCorrectionScheduler(
			lockAlwaysAcquired(), mock(RoomStatusCorrectionCoordinator.class), progressStore, schedulerProperties(),
			Clock.fixed(NOW, ZoneOffset.UTC)) {
			@Override
			long elapsedNanos() {
				return claimed.get() ? Duration.ofSeconds(30).plusNanos(1).toNanos() : 0L;
			}
		};
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			assertSame(expected, assertThrows(IllegalStateException.class, scheduler::correctDueRooms));

			assertEquals(1L, warningCount(appender, "event=room_status_correction_execution_slow"));
			assertEquals(1L, warningCount(appender, "event=room_state_reconciliation_failed"));
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void 첫_실행은_TriggerContext_Clock_현재_시각부터_15분에_스케줄_jitter를_더한다() {
		RoomStatusCorrectionCoordinator coordinator = mock(RoomStatusCorrectionCoordinator.class);
		RoomStatusCorrectionScheduler.JitterSource jitterSource = mock(
			RoomStatusCorrectionScheduler.JitterSource.class);
		RoomStatusCorrectionScheduler scheduler = scheduler(coordinator, jitterSource, delay -> {});
		doReturn(0L).when(jitterSource).nextMillis(MAX_SCHEDULE_JITTER_MILLIS);

		assertEquals(
			TRIGGER_NOW.plus(RoomStatusCorrectionScheduler.BASE_DELAY),
			scheduler.nextExecution(
				new SimpleTriggerContext(Clock.fixed(TRIGGER_NOW, ZoneOffset.UTC))));
	}

	@Test
	void 스케줄_jitter_상한은_15분에_3분을_더한_시각이다() {
		RoomStatusCorrectionCoordinator coordinator = mock(RoomStatusCorrectionCoordinator.class);
		RoomStatusCorrectionScheduler.JitterSource jitterSource = mock(
			RoomStatusCorrectionScheduler.JitterSource.class);
		RoomStatusCorrectionScheduler scheduler = scheduler(coordinator, jitterSource, delay -> {});
		doReturn(MAX_SCHEDULE_JITTER_MILLIS)
			.when(jitterSource)
			.nextMillis(MAX_SCHEDULE_JITTER_MILLIS);

		assertEquals(
			NOW.plus(RoomStatusCorrectionScheduler.BASE_DELAY)
				.plusMillis(MAX_SCHEDULE_JITTER_MILLIS),
			scheduler.nextExecution(
				new SimpleTriggerContext(Clock.fixed(NOW, ZoneOffset.UTC))));
	}

	@Test
	void 설정한_triggerJitter를_다음_실행_jitter_상한으로_사용한다() {
		RoomStatusCorrectionCoordinator coordinator = mock(RoomStatusCorrectionCoordinator.class);
		RoomStatusCorrectionScheduler.JitterSource jitterSource = mock(
			RoomStatusCorrectionScheduler.JitterSource.class);
		RoomStatusCorrectionProperties properties = schedulerProperties();
		properties.setTriggerJitter(Duration.ofSeconds(7));
		RoomStatusCorrectionScheduler scheduler = scheduler(coordinator, properties, jitterSource, delay -> {});
		doReturn(Duration.ofSeconds(7).toMillis()).when(jitterSource)
			.nextMillis(Duration.ofSeconds(7).toMillis());

		assertEquals(
			NOW.plus(properties.getTriggerDelay()).plus(properties.getTriggerJitter()),
			scheduler.nextExecution(new SimpleTriggerContext(Clock.fixed(NOW, ZoneOffset.UTC))));
		verify(jitterSource).nextMillis(Duration.ofSeconds(7).toMillis());
	}

	@Test
	void 다음_실행은_이전_완료_시각을_기준으로_계산한다() {
		Instant scheduled = NOW.plus(Duration.ofMinutes(1));
		Instant actual = NOW.plus(Duration.ofMinutes(2));
		Instant completion = NOW.plus(Duration.ofMinutes(3));
		RoomStatusCorrectionCoordinator coordinator = mock(RoomStatusCorrectionCoordinator.class);
		RoomStatusCorrectionScheduler.JitterSource jitterSource = mock(
			RoomStatusCorrectionScheduler.JitterSource.class);
		RoomStatusCorrectionScheduler scheduler = scheduler(coordinator, jitterSource, delay -> {});
		doReturn(0L).when(jitterSource).nextMillis(MAX_SCHEDULE_JITTER_MILLIS);

		SimpleTriggerContext context = new SimpleTriggerContext(scheduled, actual, completion);

		assertEquals(
			completion.plus(RoomStatusCorrectionScheduler.BASE_DELAY),
			scheduler.nextExecution(context));
	}

	@Test
	void 스케줄러_재시도는_두번째와_세번째_시도에_250ms와_500ms_cap을_사용한다() {
		RoomStatusCorrectionCoordinator coordinator = mock(RoomStatusCorrectionCoordinator.class);
		RoomStatusCorrectionScheduler.JitterSource jitterSource = mock(
			RoomStatusCorrectionScheduler.JitterSource.class);
		RoomStatusCorrectionScheduler.Sleeper sleeper = mock(RoomStatusCorrectionScheduler.Sleeper.class);
		RoomStatusCorrectionScheduler scheduler = scheduler(coordinator, jitterSource, sleeper);
		doReturn(0L).when(jitterSource).nextMillis(250L);
		doReturn(500L).when(jitterSource).nextMillis(500L);

		scheduler.correctDueRooms();

		ArgumentCaptor<IntConsumer> retryHook = ArgumentCaptor.forClass(IntConsumer.class);
		verify(coordinator).correctDueRooms(eq(NOW), retryHook.capture());
		retryHook.getValue().accept(2);
		retryHook.getValue().accept(3);

		verify(jitterSource).nextMillis(250L);
		verify(jitterSource).nextMillis(500L);
		verify(sleeper).sleep(0L);
		verify(sleeper).sleep(500L);
	}

	private TestRoomStatusCorrectionScheduler scheduler(
		RoomStatusCorrectionCoordinator coordinator,
		RoomStatusCorrectionScheduler.JitterSource jitterSource,
		RoomStatusCorrectionScheduler.Sleeper sleeper) {
		return scheduler(coordinator, schedulerProperties(), jitterSource, sleeper);
	}

	private TestRoomStatusCorrectionScheduler scheduler(
		RoomStatusCorrectionCoordinator coordinator,
		RoomStatusCorrectionProperties properties,
		RoomStatusCorrectionScheduler.JitterSource jitterSource,
		RoomStatusCorrectionScheduler.Sleeper sleeper) {
		return new TestRoomStatusCorrectionScheduler(
			coordinator, properties, Clock.fixed(NOW, ZoneOffset.UTC), jitterSource, sleeper);
	}

	private static ScheduledTaskLock lockAlwaysAcquired() {
		return (lockName, lockAtMostFor, task) -> {
			task.run();
			return ScheduledTaskLock.LockExecution.acquiredResult();
		};
	}

	private static RoomStatusCorrectionProperties schedulerProperties() {
		RoomStatusCorrectionProperties properties = new RoomStatusCorrectionProperties();
		properties.setLockName("room-status-correction");
		properties.setTriggerDelay(RoomStatusCorrectionScheduler.BASE_DELAY);
		properties.setTriggerJitter(RoomStatusCorrectionScheduler.MAX_SCHEDULE_JITTER);
		properties.setLockAtMostFor(Duration.ofMinutes(2));
		properties.setExecutionWarningThreshold(Duration.ofSeconds(30));
		return properties;
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(RoomStatusCorrectionScheduler.class);
		logger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(RoomStatusCorrectionScheduler.class);
		logger.detachAppender(appender);
		logger.setLevel(null);
		appender.stop();
	}

	private long warningCount(ListAppender<ILoggingEvent> appender, String event) {
		return appender.list.stream()
			.filter(loggingEvent -> loggingEvent.getLevel() == Level.WARN)
			.filter(loggingEvent -> loggingEvent.getFormattedMessage().startsWith(event))
			.count();
	}

	private void assertSlowWarningCount(
		RuntimeException exception, Duration elapsed, int expectedSlowWarningCount) {
		RoomStatusCorrectionCoordinator coordinator = mock(RoomStatusCorrectionCoordinator.class);
		RoomStatusCorrectionScheduler scheduler = new RoomStatusCorrectionScheduler(
			lockAlwaysAcquired(), coordinator, mock(RoomStatusCorrectionProgressStore.class), schedulerProperties(),
			Clock.fixed(NOW, ZoneOffset.UTC)) {
			private int elapsedNanosCallCount;

			@Override
			long elapsedNanos() {
				return elapsedNanosCallCount++ == 0 ? 0L : elapsed.toNanos();
			}
		};
		if (exception != null) {
			doThrow(exception).when(coordinator).correctDueRooms(eq(NOW), any());
		}
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			if (exception == null) {
				scheduler.correctDueRooms();
			} else {
				assertSame(exception, assertThrows(exception.getClass(), scheduler::correctDueRooms));
			}

			assertEquals(expectedSlowWarningCount, appender.list.stream()
				.filter(event -> event.getFormattedMessage().startsWith("event=room_status_correction_execution_slow"))
				.count());
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void room_소유_설정이_동적_Trigger를_Spring_scheduling에_등록한다() {
		assertNotNull(RoomStatusCorrectionSchedulingConfiguration.class.getAnnotation(Configuration.class));
		assertNotNull(RoomStatusCorrectionSchedulingConfiguration.class.getAnnotation(EnableScheduling.class));

		RoomStatusCorrectionScheduler scheduler = mock(RoomStatusCorrectionScheduler.class);
		RoomStatusCorrectionSchedulingConfiguration configuration = new RoomStatusCorrectionSchedulingConfiguration(
			scheduler);
		ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

		configuration.configureTasks(registrar);

		assertEquals(1, registrar.getTriggerTaskList().size());
		var triggerTask = registrar.getTriggerTaskList().get(0);
		assertEquals(scheduler, triggerTask.getTrigger());
		triggerTask.getRunnable().run();
		verify(scheduler).correctDueRooms();
	}

	private static final class TestRoomStatusCorrectionScheduler
		extends RoomStatusCorrectionScheduler {

		private final JitterSource jitterSource;
		private final Sleeper sleeper;

		private TestRoomStatusCorrectionScheduler(
			RoomStatusCorrectionCoordinator coordinator,
			RoomStatusCorrectionProperties properties,
			Clock clock,
			JitterSource jitterSource,
			Sleeper sleeper) {
			super(lockAlwaysAcquired(), coordinator, mock(RoomStatusCorrectionProgressStore.class), properties, clock);
			this.jitterSource = jitterSource;
			this.sleeper = sleeper;
		}

		@Override
		long nextJitterMillis(long maxInclusive) {
			return jitterSource.nextMillis(maxInclusive);
		}

		@Override
		void sleepBeforeRetry(long delayMillis) {
			sleeper.sleep(delayMillis);
		}
	}
}
