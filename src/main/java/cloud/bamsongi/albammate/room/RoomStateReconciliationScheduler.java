package cloud.bamsongi.albammate.room;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.stereotype.Component;

/** 요청이 없는 방도 같은 상태 보정 규칙으로 주기적으로 정리한다. */
@Component
public class RoomStateReconciliationScheduler implements Trigger {

    static final Duration BASE_DELAY = Duration.ofMinutes(15);
    static final Duration MAX_SCHEDULE_JITTER = Duration.ofMinutes(3);
    static final long SECOND_ATTEMPT_MAX_DELAY_MILLIS = 250;
    static final long THIRD_ATTEMPT_MAX_DELAY_MILLIS = 500;

    private final RoomStateReconciliationCoordinator coordinator;
    private final Clock clock;

    public RoomStateReconciliationScheduler(
            RoomStateReconciliationCoordinator coordinator, Clock clock) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    long nextJitterMillis(long maxInclusive) {
        return ThreadLocalRandom.current().nextLong(maxInclusive + 1);
    }

    void sleepBeforeRetry(long delayMillis) {
        sleep(delayMillis);
    }

    /** 현재 시각을 한 번 얻어 due 방의 상태를 보정하고, 스케줄러 경로만 충돌 재시도에 지연을 둔다. */
    public void reconcileDueRooms() {
        Instant requestTime = Instant.now(clock);
        coordinator.reconcileDueRooms(requestTime, this::waitBeforeRetry);
    }

    /** 이전 실행 완료 시각을 기준으로 15분 뒤에 0~3분의 full jitter를 더해 다음 실행을 예약한다. */
    @Override
    public Instant nextExecution(TriggerContext triggerContext) {
        Objects.requireNonNull(triggerContext, "triggerContext");
        Instant anchor = triggerContext.lastCompletion();
        if (anchor == null) {
            anchor = triggerContext.getClock().instant();
        }
        long jitterMillis = nextJitterMillis(MAX_SCHEDULE_JITTER.toMillis());
        return anchor.plus(BASE_DELAY).plusMillis(jitterMillis);
    }

    private void waitBeforeRetry(int nextAttempt) {
        long maxDelayMillis =
                switch (nextAttempt) {
                    case 2 -> SECOND_ATTEMPT_MAX_DELAY_MILLIS;
                    case 3 -> THIRD_ATTEMPT_MAX_DELAY_MILLIS;
                    default -> throw new IllegalArgumentException("지원하지 않는 재시도 횟수");
                };
        sleepBeforeRetry(nextJitterMillis(maxDelayMillis));
    }

    private static void sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("방 상태 보정 재시도가 중단되었습니다.", exception);
        }
    }

    @FunctionalInterface
    interface JitterSource {

        long nextMillis(long maxInclusive);
    }

    @FunctionalInterface
    interface Sleeper {

        void sleep(long delayMillis);
    }
}
