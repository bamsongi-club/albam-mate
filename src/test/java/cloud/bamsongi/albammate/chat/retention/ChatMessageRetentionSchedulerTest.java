package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ChatMessageRetentionSchedulerTest {

	@Test
	void 잠금을_얻지_못한_인스턴스는_대기_없이_skip_메트릭만_기록한다() {
		ScheduledTaskLock scheduledTaskLock = mock(ScheduledTaskLock.class);
		ChatMessageRetentionCoordinator coordinator = mock(ChatMessageRetentionCoordinator.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		when(scheduledTaskLock.tryExecute(
			eq(ChatMessageRetentionScheduler.LOCK_NAME), eq(properties.getLockAtMostFor()),
			eq(properties.getLockAtLeastFor()), any(Runnable.class)))
			.thenReturn(ScheduledTaskLock.LockExecution.skippedResult());
		ChatMessageRetentionScheduler scheduler = new ChatMessageRetentionScheduler(
			scheduledTaskLock, coordinator, properties, metrics);

		scheduler.purgeExpiredMessages();

		verify(scheduledTaskLock).tryExecute(
			eq(ChatMessageRetentionScheduler.LOCK_NAME), eq(properties.getLockAtMostFor()),
			eq(properties.getLockAtLeastFor()), any(Runnable.class));
		assertEquals(1.0, meterRegistry.get("chat.message.retention.lock.skipped").counter().count());
		meterRegistry.close();
	}

	@Test
	void 비활성화하면_잠금과_삭제를_호출하지_않는다() {
		ScheduledTaskLock scheduledTaskLock = mock(ScheduledTaskLock.class);
		ChatMessageRetentionCoordinator coordinator = mock(ChatMessageRetentionCoordinator.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setEnabled(false);
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		ChatMessageRetentionScheduler scheduler = new ChatMessageRetentionScheduler(
			scheduledTaskLock, coordinator, properties, metrics);

		scheduler.purgeExpiredMessages();

		verifyNoInteractions(scheduledTaskLock, coordinator);
		meterRegistry.close();
	}

	@Test
	void 스케줄러_실패는_예외_본문_없이_실패_메트릭을_기록한다() {
		ScheduledTaskLock scheduledTaskLock = mock(ScheduledTaskLock.class);
		ChatMessageRetentionCoordinator coordinator = mock(ChatMessageRetentionCoordinator.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		when(scheduledTaskLock.tryExecute(
			eq(ChatMessageRetentionScheduler.LOCK_NAME), eq(properties.getLockAtMostFor()),
			eq(properties.getLockAtLeastFor()), any(Runnable.class)))
			.thenThrow(new IllegalStateException("message-content-secret session=token"));
		ChatMessageRetentionScheduler scheduler = new ChatMessageRetentionScheduler(
			scheduledTaskLock, coordinator, properties, metrics);

		scheduler.purgeExpiredMessages();

		assertEquals(1.0, meterRegistry.get("chat.message.retention.failures").counter().count());
		meterRegistry.close();
	}

	@Test
	void 실행_상한에서_중단되면_같은_cron_실행_안에서_잠금_구간을_이어받는다() {
		ScheduledTaskLock scheduledTaskLock = mock(ScheduledTaskLock.class);
		ChatMessageRetentionCoordinator coordinator = mock(ChatMessageRetentionCoordinator.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMaxLockSectionsPerRun(5);
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		when(coordinator.purgeExpiredMessages()).thenReturn(
			summary(true), summary(true), summary(false));
		runLockedTask(scheduledTaskLock, properties);
		ChatMessageRetentionScheduler scheduler = new ChatMessageRetentionScheduler(
			scheduledTaskLock, coordinator, properties, metrics);

		scheduler.purgeExpiredMessages();

		verify(coordinator, times(3)).purgeExpiredMessages();
		assertEquals(0.0, meterRegistry.get("chat.message.retention.backlog.remaining").counter().count());
		meterRegistry.close();
	}

	@Test
	void 잠금_구간_상한까지_적체가_남으면_경고_메트릭을_기록한다() {
		ScheduledTaskLock scheduledTaskLock = mock(ScheduledTaskLock.class);
		ChatMessageRetentionCoordinator coordinator = mock(ChatMessageRetentionCoordinator.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMaxLockSectionsPerRun(2);
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		when(coordinator.purgeExpiredMessages()).thenReturn(summary(true));
		runLockedTask(scheduledTaskLock, properties);
		ChatMessageRetentionScheduler scheduler = new ChatMessageRetentionScheduler(
			scheduledTaskLock, coordinator, properties, metrics);

		scheduler.purgeExpiredMessages();

		verify(coordinator, times(2)).purgeExpiredMessages();
		assertEquals(1.0, meterRegistry.get("chat.message.retention.backlog.remaining").counter().count());
		meterRegistry.close();
	}

	@Test
	void 잠금을_얻었는데_결과가_없으면_다음_구간을_시도하지_않는다() {
		ScheduledTaskLock scheduledTaskLock = mock(ScheduledTaskLock.class);
		ChatMessageRetentionCoordinator coordinator = mock(ChatMessageRetentionCoordinator.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		when(scheduledTaskLock.tryExecute(
			eq(ChatMessageRetentionScheduler.LOCK_NAME), eq(properties.getLockAtMostFor()),
			eq(properties.getLockAtLeastFor()), any(Runnable.class)))
			.thenReturn(ScheduledTaskLock.LockExecution.acquiredResult());
		ChatMessageRetentionScheduler scheduler = new ChatMessageRetentionScheduler(
			scheduledTaskLock, coordinator, properties, metrics);

		scheduler.purgeExpiredMessages();

		verify(scheduledTaskLock, times(1)).tryExecute(
			eq(ChatMessageRetentionScheduler.LOCK_NAME), eq(properties.getLockAtMostFor()),
			eq(properties.getLockAtLeastFor()), any(Runnable.class));
		assertEquals(0.0, meterRegistry.get("chat.message.retention.backlog.remaining").counter().count());
		meterRegistry.close();
	}

	@Test
	void UTC_기본_cron과_설정_대체_표시를_고정한다() throws NoSuchMethodException {
		Scheduled scheduled = ChatMessageRetentionScheduler.class
			.getDeclaredMethod("purgeExpiredMessages")
			.getAnnotation(Scheduled.class);

		assertEquals("${app.chat.retention.cron:0 0 3 * * *}", scheduled.cron());
		assertEquals("UTC", scheduled.zone());
	}

	/** 잠금을 얻은 실행이 전달된 작업을 그 자리에서 수행하도록 stub한다. */
	private void runLockedTask(ScheduledTaskLock scheduledTaskLock, ChatMessageRetentionProperties properties) {
		when(scheduledTaskLock.tryExecute(
			eq(ChatMessageRetentionScheduler.LOCK_NAME), eq(properties.getLockAtMostFor()),
			eq(properties.getLockAtLeastFor()), any(Runnable.class)))
			.thenAnswer(invocation -> {
				invocation.getArgument(3, Runnable.class).run();
				return ScheduledTaskLock.LockExecution.acquiredResult();
			});
	}

	private ChatMessageRetentionCoordinator.RetentionRunSummary summary(boolean leaseGuardAborted) {
		return new ChatMessageRetentionCoordinator.RetentionRunSummary(0, 0, 0, 0, 0, leaseGuardAborted);
	}
}
