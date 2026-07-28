package cloud.bamsongi.albammate.room.service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomStatusResponse;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** 방 취소·종료 시 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도한다. */
@Service
public class RoomStatusChangeService {

    private static final int MAX_ATTEMPTS = 3;

    private final RoomStatusChangeExecutor executor;
    private final Clock clock;

    public RoomStatusChangeService(RoomStatusChangeExecutor executor, Clock clock) {
        this.executor = executor;
        this.clock = clock;
    }

    public RoomStatusResponse cancelRoom(long currentUserId, long roomId) {
        Instant requestTime = Instant.now(clock);
        RuntimeException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return executor.cancelRoom(currentUserId, roomId, requestTime);
            } catch (OptimisticLockException | ObjectOptimisticLockingFailureException exception) {
                lastConflict = exception;
            }
        }
        throw new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION, lastConflict);
    }

    public RoomStatusResponse finishRoom(long currentUserId, long roomId) {
        Instant requestTime = Instant.now(clock);
        RuntimeException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return executor.finishRoom(currentUserId, roomId, requestTime);
            } catch (OptimisticLockException | ObjectOptimisticLockingFailureException exception) {
                lastConflict = exception;
            }
        }
        throw new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION, lastConflict);
    }
}
