package cloud.bamsongi.albammate.room.service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomUpdateRequest;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** 방 수정 시 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도한다. */
@Service
public class RoomUpdateService {

    private static final int MAX_ATTEMPTS = 3;

    private final RoomUpdateExecutor executor;
    private final Clock clock;

    public RoomUpdateService(RoomUpdateExecutor executor, Clock clock) {
        this.executor = executor;
        this.clock = clock;
    }

    public ParticipantRoomResponse updateRoom(
            long currentUserId, long roomId, RoomUpdateRequest request) {
        Instant requestTime = Instant.now(clock);
        RuntimeException lastConflict = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return executor.updateRoom(currentUserId, roomId, request, requestTime);
            } catch (OptimisticLockException | ObjectOptimisticLockingFailureException exception) {
                lastConflict = exception;
            }
        }

        throw new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION, lastConflict);
    }
}
