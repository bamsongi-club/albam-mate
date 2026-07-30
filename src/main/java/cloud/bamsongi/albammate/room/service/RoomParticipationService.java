package cloud.bamsongi.albammate.room.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.room.RoomOptimisticLockRetrier;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;

@Service
public class RoomParticipationService {

	private final RoomParticipationExecutor executor;
	private final Clock clock;
	private final RoomOptimisticLockRetrier retrier;

	public RoomParticipationService(
		RoomParticipationExecutor executor, Clock clock, RoomOptimisticLockRetrier retrier) {
		this.executor = Objects.requireNonNull(executor, "executor");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.retrier = Objects.requireNonNull(retrier, "retrier");
	}

	/** 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도해 참가를 확정한다. */
	public RoomParticipationResponse participate(long currentUserId, long roomId) {
		Instant requestTime = Instant.now(clock);
		return retrier.execute(
			() -> executor.participate(currentUserId, roomId, requestTime),
			"room_participation_retry", roomId);
	}
}
