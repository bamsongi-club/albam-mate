package cloud.bamsongi.albammate.room.service.command;

import java.util.Objects;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;

@Service
public class RoomParticipationService {

	private final RoomParticipationExecutor executor;
	private final RoomCommandExecutionCoordinator executionCoordinator;

	public RoomParticipationService(
		RoomParticipationExecutor executor, RoomCommandExecutionCoordinator executionCoordinator) {
		this.executor = Objects.requireNonNull(executor, "executor");
		this.executionCoordinator = Objects.requireNonNull(executionCoordinator, "executionCoordinator");
	}

	/** 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도해 참가를 확정한다. */
	public RoomParticipationResponse participate(long currentUserId, long roomId) {
		return executionCoordinator.execute(
			roomId,
			"room_participation_retry",
			requestTime -> executor.participate(currentUserId, roomId, requestTime));
	}
}
