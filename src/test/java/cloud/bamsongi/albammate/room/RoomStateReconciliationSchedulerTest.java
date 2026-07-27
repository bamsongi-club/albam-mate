package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

class RoomStateReconciliationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

    @Test
    void 고정된_Clock의_현재_시각을_coordinator에_전달한다() {
        RoomStateReconciliationCoordinator coordinator =
                mock(RoomStateReconciliationCoordinator.class);
        RoomStateReconciliationScheduler scheduler =
                new RoomStateReconciliationScheduler(coordinator, Clock.fixed(NOW, ZoneOffset.UTC));

        scheduler.reconcileDueRooms();

        verify(coordinator).reconcileDueRooms(NOW);
    }

    @Test
    void 스케줄은_1분_fixedDelay와_initialDelay로_설정된다() throws NoSuchMethodException {
        Scheduled scheduled =
                RoomStateReconciliationScheduler.class
                        .getMethod("reconcileDueRooms")
                        .getAnnotation(Scheduled.class);

        assertNotNull(scheduled);
        assertEquals(1, scheduled.fixedDelay());
        assertEquals(1, scheduled.initialDelay());
        assertEquals(TimeUnit.MINUTES, scheduled.timeUnit());
    }

    @Test
    void room_소유_설정이_Spring_scheduling을_활성화한다() {
        assertNotNull(RoomSchedulingConfiguration.class.getAnnotation(Configuration.class));
        assertNotNull(RoomSchedulingConfiguration.class.getAnnotation(EnableScheduling.class));
    }
}
