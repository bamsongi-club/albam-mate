package cloud.bamsongi.albammate.room;

import java.time.Instant;
import java.util.Objects;
import java.util.function.IntConsumer;

import org.springframework.stereotype.Service;

/** 트랜잭션 경계 밖에서 낙관 락 충돌만 제한적으로 재시도한다. */
@Service
public class RoomStateReconciliationCoordinator {

	private final RoomStateReconciliationExecutor executor;
	private final RoomOptimisticLockRetrier retrier;

	public RoomStateReconciliationCoordinator(
		RoomStateReconciliationExecutor executor, RoomOptimisticLockRetrier retrier) {
		this.executor = Objects.requireNonNull(executor, "executor");
		this.retrier = Objects.requireNonNull(retrier, "retrier");
	}

	/** 단건 상태 보정을 최대 세 개의 독립 트랜잭션으로 시도한다. */
	public void reconcileRoom(Long roomId, Instant requestTime) {
		Objects.requireNonNull(roomId, "roomId");
		Objects.requireNonNull(requestTime, "requestTime");
		retrier.execute(
			() -> {
				executor.reconcileRoom(roomId, requestTime);
				return null;
			},
			"room_state_reconciliation_retry", roomId);
	}

	/** 목록·내 모임 필터와 페이지 계산 전에 due 방 전체를 보정한다. */
	public int reconcileDueRooms(Instant requestTime) {
		return reconcileDueRooms(requestTime, ignoredAttempt -> {});
	}

	/** 스케줄러처럼 재시도 전 지연이 필요한 호출자만 시도별 지연을 주입한다. */
	int reconcileDueRooms(Instant requestTime, IntConsumer beforeRetry) {
		Objects.requireNonNull(requestTime, "requestTime");
		Objects.requireNonNull(beforeRetry, "beforeRetry");
		return retrier.execute(
			() -> executor.reconcileDueRooms(requestTime),
			"room_state_reconciliation_retry", null, beforeRetry);
	}
}
