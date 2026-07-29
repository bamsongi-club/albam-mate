package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;

class RoomStateReconciliationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");
    private static final Instant TRIGGER_NOW = Instant.parse("2026-07-27T01:00:00Z");
    private static final long MAX_SCHEDULE_JITTER_MILLIS =
            RoomStateReconciliationScheduler.MAX_SCHEDULE_JITTER.toMillis();

    @Test
    void 고정된_Clock의_현재_시각을_coordinator에_전달한다() {
        RoomStateReconciliationCoordinator coordinator =
                mock(RoomStateReconciliationCoordinator.class);
        RoomStateReconciliationScheduler.JitterSource jitterSource =
                mock(RoomStateReconciliationScheduler.JitterSource.class);
        RoomStateReconciliationScheduler.Sleeper sleeper =
                mock(RoomStateReconciliationScheduler.Sleeper.class);
        RoomStateReconciliationScheduler scheduler = scheduler(coordinator, jitterSource, sleeper);

        scheduler.reconcileDueRooms();

        verify(coordinator).reconcileDueRooms(eq(NOW), any());
    }

    @Test
    void 첫_실행은_TriggerContext_Clock_현재_시각부터_15분에_스케줄_jitter를_더한다() {
        RoomStateReconciliationCoordinator coordinator =
                mock(RoomStateReconciliationCoordinator.class);
        RoomStateReconciliationScheduler.JitterSource jitterSource =
                mock(RoomStateReconciliationScheduler.JitterSource.class);
        RoomStateReconciliationScheduler scheduler =
                scheduler(coordinator, jitterSource, delay -> {});
        doReturn(0L).when(jitterSource).nextMillis(MAX_SCHEDULE_JITTER_MILLIS);

        assertEquals(
                TRIGGER_NOW.plus(RoomStateReconciliationScheduler.BASE_DELAY),
                scheduler.nextExecution(
                        new SimpleTriggerContext(Clock.fixed(TRIGGER_NOW, ZoneOffset.UTC))));
    }

    @Test
    void 스케줄_jitter_상한은_15분에_3분을_더한_시각이다() {
        RoomStateReconciliationCoordinator coordinator =
                mock(RoomStateReconciliationCoordinator.class);
        RoomStateReconciliationScheduler.JitterSource jitterSource =
                mock(RoomStateReconciliationScheduler.JitterSource.class);
        RoomStateReconciliationScheduler scheduler =
                scheduler(coordinator, jitterSource, delay -> {});
        doReturn(MAX_SCHEDULE_JITTER_MILLIS)
                .when(jitterSource)
                .nextMillis(MAX_SCHEDULE_JITTER_MILLIS);

        assertEquals(
                NOW.plus(RoomStateReconciliationScheduler.BASE_DELAY)
                        .plusMillis(MAX_SCHEDULE_JITTER_MILLIS),
                scheduler.nextExecution(
                        new SimpleTriggerContext(Clock.fixed(NOW, ZoneOffset.UTC))));
    }

    @Test
    void 다음_실행은_이전_완료_시각을_기준으로_계산한다() {
        Instant scheduled = NOW.plus(Duration.ofMinutes(1));
        Instant actual = NOW.plus(Duration.ofMinutes(2));
        Instant completion = NOW.plus(Duration.ofMinutes(3));
        RoomStateReconciliationCoordinator coordinator =
                mock(RoomStateReconciliationCoordinator.class);
        RoomStateReconciliationScheduler.JitterSource jitterSource =
                mock(RoomStateReconciliationScheduler.JitterSource.class);
        RoomStateReconciliationScheduler scheduler =
                scheduler(coordinator, jitterSource, delay -> {});
        doReturn(0L).when(jitterSource).nextMillis(MAX_SCHEDULE_JITTER_MILLIS);

        SimpleTriggerContext context = new SimpleTriggerContext(scheduled, actual, completion);

        assertEquals(
                completion.plus(RoomStateReconciliationScheduler.BASE_DELAY),
                scheduler.nextExecution(context));
    }

    @Test
    void 스케줄러_재시도는_두번째와_세번째_시도에_250ms와_500ms_cap을_사용한다() {
        RoomStateReconciliationCoordinator coordinator =
                mock(RoomStateReconciliationCoordinator.class);
        RoomStateReconciliationScheduler.JitterSource jitterSource =
                mock(RoomStateReconciliationScheduler.JitterSource.class);
        RoomStateReconciliationScheduler.Sleeper sleeper =
                mock(RoomStateReconciliationScheduler.Sleeper.class);
        RoomStateReconciliationScheduler scheduler = scheduler(coordinator, jitterSource, sleeper);
        doReturn(0L).when(jitterSource).nextMillis(250L);
        doReturn(500L).when(jitterSource).nextMillis(500L);

        scheduler.reconcileDueRooms();

        ArgumentCaptor<IntConsumer> retryHook = ArgumentCaptor.forClass(IntConsumer.class);
        verify(coordinator).reconcileDueRooms(eq(NOW), retryHook.capture());
        retryHook.getValue().accept(2);
        retryHook.getValue().accept(3);

        verify(jitterSource).nextMillis(250L);
        verify(jitterSource).nextMillis(500L);
        verify(sleeper).sleep(0L);
        verify(sleeper).sleep(500L);
    }

    private TestRoomStateReconciliationScheduler scheduler(
            RoomStateReconciliationCoordinator coordinator,
            RoomStateReconciliationScheduler.JitterSource jitterSource,
            RoomStateReconciliationScheduler.Sleeper sleeper) {
        return new TestRoomStateReconciliationScheduler(
                coordinator, Clock.fixed(NOW, ZoneOffset.UTC), jitterSource, sleeper);
    }

    @Test
    void room_소유_설정이_동적_Trigger를_Spring_scheduling에_등록한다() {
        assertNotNull(RoomSchedulingConfiguration.class.getAnnotation(Configuration.class));
        assertNotNull(RoomSchedulingConfiguration.class.getAnnotation(EnableScheduling.class));

        RoomStateReconciliationScheduler scheduler = mock(RoomStateReconciliationScheduler.class);
        RoomSchedulingConfiguration configuration = new RoomSchedulingConfiguration(scheduler);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        configuration.configureTasks(registrar);

        assertEquals(1, registrar.getTriggerTaskList().size());
        var triggerTask = registrar.getTriggerTaskList().get(0);
        assertEquals(scheduler, triggerTask.getTrigger());
        triggerTask.getRunnable().run();
        verify(scheduler).reconcileDueRooms();
    }

    private static final class TestRoomStateReconciliationScheduler
            extends RoomStateReconciliationScheduler {

        private final JitterSource jitterSource;
        private final Sleeper sleeper;

        private TestRoomStateReconciliationScheduler(
                RoomStateReconciliationCoordinator coordinator,
                Clock clock,
                JitterSource jitterSource,
                Sleeper sleeper) {
            super(coordinator, clock);
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
