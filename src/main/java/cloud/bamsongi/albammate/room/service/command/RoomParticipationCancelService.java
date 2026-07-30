package cloud.bamsongi.albammate.room.service.command;

import java.util.Objects;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;

/** 현재 사용자의 활성 참가 관계를 낙관 락 충돌 시에만 재시도해 취소한다. */
@Service
public class RoomParticipationCancelService {

	private final RoomParticipationCancelExecutor executor;
	private final RoomCommandExecutionCoordinator executionCoordinator;

	public RoomParticipationCancelService(
		RoomParticipationCancelExecutor executor, RoomCommandExecutionCoordinator executionCoordinator) {
		this.executor = Objects.requireNonNull(executor, "executor");
		this.executionCoordinator = Objects.requireNonNull(executionCoordinator, "executionCoordinator");
	}

	/** 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도해 참가 취소를 확정한다. */
	public RoomParticipationResponse cancelParticipation(long currentUserId, long roomId) {
		return executionCoordinator.execute(
			roomId,
			"room_participation_cancel_retry",
			requestTime -> executor.cancelParticipation(currentUserId, roomId, requestTime));
	}
}
