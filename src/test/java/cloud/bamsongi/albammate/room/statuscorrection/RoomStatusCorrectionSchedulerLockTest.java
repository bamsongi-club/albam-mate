package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;

class RoomStatusCorrectionSchedulerLockTest {

	@Test
	void room_상태_보정은_잠금_획득_실행만_한번_시작하고_미획득이면_건너뛴다() {
		ScheduledTaskLock taskLock = mock(ScheduledTaskLock.class);
		RoomStatusCorrectionCoordinator coordinator = mock(RoomStatusCorrectionCoordinator.class);
		RoomStatusCorrectionProgressStore progressStore = mock(RoomStatusCorrectionProgressStore.class);
		RoomStatusCorrectionProperties properties = properties();
		when(progressStore.claimExecution(any())).thenReturn(
			new RoomStatusCorrectionProgressStore.ProgressSnapshot(
				Instant.parse("2026-08-05T00:00:00Z"), null, null, 1L, 1L));
		RoomStatusCorrectionScheduler scheduler = new RoomStatusCorrectionScheduler(
			taskLock,
			coordinator,
			progressStore,
			properties,
			Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC));
		doAnswer(invocation -> {
			invocation.getArgument(2, Runnable.class).run();
			return ScheduledTaskLock.LockExecution.acquiredResult();
		}).when(taskLock).tryExecute(eq("room-status-correction"), eq(Duration.ofMinutes(2)), any());

		scheduler.correctDueRooms();

		verify(taskLock).tryExecute(eq("room-status-correction"), eq(Duration.ofMinutes(2)), any());
		verify(coordinator).correctBoundedDueRooms(
			eq(Instant.parse("2026-08-05T00:00:00Z")),
			any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), eq(10), any());
		when(taskLock.tryExecute(eq("room-status-correction"), eq(Duration.ofMinutes(2)), any()))
			.thenReturn(ScheduledTaskLock.LockExecution.skippedResult());

		scheduler.correctDueRooms();

		verify(coordinator).correctBoundedDueRooms(
			eq(Instant.parse("2026-08-05T00:00:00Z")),
			any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), eq(10), any());
	}

	@Test
	void 실행시간이_경고_기준을_초과할_때만_ROOM_전용_WARN을_한번_기록한다() {
		ScheduledTaskLock taskLock = mock(ScheduledTaskLock.class);
		RoomStatusCorrectionCoordinator coordinator = mock(RoomStatusCorrectionCoordinator.class);
		RoomStatusCorrectionProperties properties = properties();
		doAnswer(invocation -> {
			invocation.getArgument(2, Runnable.class).run();
			return ScheduledTaskLock.LockExecution.acquiredResult();
		}).when(taskLock).tryExecute(any(), any(), any());
		AtomicInteger calls = new AtomicInteger();
		RoomStatusCorrectionScheduler scheduler = new RoomStatusCorrectionScheduler(
			taskLock, coordinator, mock(RoomStatusCorrectionProgressStore.class), properties,
			Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC)) {
			@Override
			long elapsedNanos() {
				return calls.getAndIncrement() == 0
					? 0L : Duration.ofSeconds(30).plusNanos(1).toNanos();
			}
		};
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			scheduler.correctDueRooms();

			long warnings = appender.list.stream()
				.filter(event -> event.getLevel() == Level.WARN)
				.filter(event -> event.getFormattedMessage().startsWith(
					"event=room_status_correction_execution_slow"))
				.count();
			assertEquals(1L, warnings);
		} finally {
			detachLogAppender(appender);
		}
	}

	private RoomStatusCorrectionProperties properties() {
		RoomStatusCorrectionProperties properties = new RoomStatusCorrectionProperties();
		properties.setLockName("room-status-correction");
		properties.setTriggerDelay(Duration.ofMinutes(15));
		properties.setTriggerJitter(Duration.ofMinutes(3));
		properties.setLockAtMostFor(Duration.ofMinutes(2));
		properties.setExecutionWarningThreshold(Duration.ofSeconds(30));
		properties.setCandidateLimit(10);
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
}
