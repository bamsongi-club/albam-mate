package cloud.bamsongi.albammate.room;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.util.Objects;
import java.util.function.IntConsumer;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** 트랜잭션 경계 밖에서 낙관 락 충돌만 제한적으로 재시도한다. */
@Service
public class RoomStateReconciliationCoordinator {

    private static final int MAX_ATTEMPTS = 3;

    private final RoomStateReconciliationExecutor executor;

    public RoomStateReconciliationCoordinator(RoomStateReconciliationExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** 단건 상태 보정을 최대 세 개의 독립 트랜잭션으로 시도한다. */
    public void reconcileRoom(Long roomId, Instant requestTime) {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(requestTime, "requestTime");
        executeWithRetry(() -> executor.reconcileRoom(roomId, requestTime), ignoredAttempt -> {});
    }

    /** 목록·내 모임 필터와 페이지 계산 전에 due 방 전체를 보정한다. */
    public void reconcileDueRooms(Instant requestTime) {
        reconcileDueRooms(requestTime, ignoredAttempt -> {});
    }

    /** 스케줄러처럼 재시도 전 지연이 필요한 호출자만 시도별 지연을 주입한다. */
    void reconcileDueRooms(Instant requestTime, IntConsumer beforeRetry) {
        Objects.requireNonNull(requestTime, "requestTime");
        Objects.requireNonNull(beforeRetry, "beforeRetry");
        executeWithRetry(() -> executor.reconcileDueRooms(requestTime), beforeRetry);
    }

    private void executeWithRetry(Runnable reconciliationAttempt, IntConsumer beforeRetry) {
        RuntimeException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                reconciliationAttempt.run();
                return;
            } catch (OptimisticLockException exception) {
                lastConflict = exception;
            } catch (ObjectOptimisticLockingFailureException exception) {
                lastConflict = exception;
            }
            if (attempt < MAX_ATTEMPTS) {
                beforeRetry.accept(attempt + 1);
            }
        }

        // 세 번 모두 같은 낙관 락 충돌이면 마지막 원인을 API 오류에 보존한다.
        throw new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION, lastConflict);
    }
}
