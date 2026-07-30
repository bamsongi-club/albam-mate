package cloud.bamsongi.albammate.room.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.room.RoomOptimisticLockRetrier;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;

/** 현재 사용자의 활성 참가 관계를 낙관 락 충돌 시에만 재시도해 취소한다. */
@Service
public class RoomParticipationCancelService {

	private final RoomParticipationCancelExecutor executor;
	private final Clock clock;
	private final RoomOptimisticLockRetrier retrier;

	public RoomParticipationCancelService(
		RoomParticipationCancelExecutor executor, Clock clock, RoomOptimisticLockRetrier retrier) {
		this.executor = Objects.requireNonNull(executor, "executor");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.retrier = Objects.requireNonNull(retrier, "retrier");
	}

	/** 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도해 참가 취소를 확정한다. */
	public RoomParticipationResponse cancelParticipation(long currentUserId, long roomId) {
		Instant requestTime = Instant.now(clock);
		return retrier.execute(
			() -> executor.cancelParticipation(currentUserId, roomId, requestTime),
			"room_participation_cancel_retry", roomId);
	}
}
