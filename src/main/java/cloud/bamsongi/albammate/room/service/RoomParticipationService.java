package cloud.bamsongi.albammate.room.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import jakarta.persistence.OptimisticLockException;

@Service
public class RoomParticipationService {

	private static final int MAX_ATTEMPTS = 3;

	private final RoomParticipationExecutor executor;
	private final Clock clock;

	public RoomParticipationService(RoomParticipationExecutor executor, Clock clock) {
		this.executor = Objects.requireNonNull(executor, "executor");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/** 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도해 참가를 확정한다. */
	public RoomParticipationResponse participate(long currentUserId, long roomId) {
		Instant requestTime = Instant.now(clock);
		RuntimeException lastConflict = null;

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return executor.participate(currentUserId, roomId, requestTime);
			} catch (OptimisticLockException exception) {
				lastConflict = exception;
			} catch (ObjectOptimisticLockingFailureException exception) {
				lastConflict = exception;
			}
		}

		throw new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION, lastConflict);
	}
}
