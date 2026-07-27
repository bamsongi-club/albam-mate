package cloud.bamsongi.albammate.room;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 요청이 없는 방도 같은 상태 보정 규칙으로 주기적으로 정리한다. */
@Component
public class RoomStateReconciliationScheduler {

    private final RoomStateReconciliationCoordinator coordinator;
    private final Clock clock;

    public RoomStateReconciliationScheduler(
            RoomStateReconciliationCoordinator coordinator, Clock clock) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 이전 실행이 끝난 뒤 1분마다 현재 시각 기준의 due 방을 보정한다. */
    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void reconcileDueRooms() {
        coordinator.reconcileDueRooms(Instant.now(clock));
    }
}
