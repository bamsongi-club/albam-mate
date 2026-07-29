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

/** 현재 사용자의 활성 참가 관계를 낙관 락 충돌 시에만 재시도해 취소한다. */
@Service
public class RoomParticipationCancelService {

	private static final int MAX_ATTEMPTS = 3;

	private final RoomParticipationCancelExecutor executor;
	private final Clock clock;

	public RoomParticipationCancelService(RoomParticipationCancelExecutor executor, Clock clock) {
		this.executor = Objects.requireNonNull(executor, "executor");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/** 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도해 참가 취소를 확정한다. */
	public RoomParticipationResponse cancelParticipation(long currentUserId, long roomId) {
		Instant requestTime = Instant.now(clock);
		RuntimeException lastConflict = null;

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return executor.cancelParticipation(currentUserId, roomId, requestTime);
			} catch (OptimisticLockException exception) {
				lastConflict = exception;
			} catch (ObjectOptimisticLockingFailureException exception) {
				lastConflict = exception;
			}
		}

		throw new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION, lastConflict);
	}
}
