package cloud.bamsongi.albammate.room.service.command;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import io.micrometer.core.instrument.Metrics;

/** 현재 사용자의 활성 참가 관계를 낙관 락 충돌 시에만 재시도해 취소한다. */
@Service
public class RoomParticipationCancelService {

	private final RoomParticipationCancelExecutor executor;
	private final RoomCommandExecutionCoordinator executionCoordinator;
	private final RoomWaitlistMetrics metrics;

	public RoomParticipationCancelService(
		RoomParticipationCancelExecutor executor,
		RoomCommandExecutionCoordinator executionCoordinator,
		RoomWaitlistMetrics... metrics) {
		this.executor = Objects.requireNonNull(executor, "executor");
		this.executionCoordinator = Objects.requireNonNull(executionCoordinator, "executionCoordinator");
		this.metrics = metrics.length == 0
			? new RoomWaitlistMetrics(Metrics.globalRegistry)
			: Objects.requireNonNull(metrics[0], "metrics");
	}

	/** 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도해 참가 취소를 확정한다. */
	public RoomParticipationResponse cancelParticipation(long currentUserId, long roomId) {
		AtomicBoolean finalAttemptPromoted = new AtomicBoolean();
		AtomicBoolean anyAttemptPromoted = new AtomicBoolean();
		try {
			RoomParticipationResponse response = executionCoordinator.execute(
				roomId,
				"room_participation_cancel_retry",
				requestTime -> {
					AtomicBoolean attemptPromoted = new AtomicBoolean();
					try {
						return executor.cancelParticipation(
							currentUserId, roomId, requestTime, () -> attemptPromoted.set(true));
					} finally {
						finalAttemptPromoted.set(attemptPromoted.get());
						if (attemptPromoted.get()) {
							anyAttemptPromoted.set(true);
						}
					}
				});
			if (finalAttemptPromoted.get()) {
				metrics.recordPromoteAccepted();
			}
			return response;
		} catch (RuntimeException exception) {
			if (anyAttemptPromoted.get()) {
				metrics.recordPromoteFailed();
			}
			throw exception;
		}
	}
}
