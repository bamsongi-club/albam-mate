package cloud.bamsongi.albammate.room;

import java.time.Instant;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;

/** 트랜잭션 경계 밖에서 낙관 락 충돌만 제한적으로 재시도한다. */
@Service
@Slf4j
public class RoomStateReconciliationCoordinator {

	private static final int MAX_ATTEMPTS = 3;

	private final RoomStateReconciliationExecutor executor;

	public RoomStateReconciliationCoordinator(RoomStateReconciliationExecutor executor) {
		this.executor = Objects.requireNonNull(executor, "executor");
	}

	/** 단건 상태 보정을 최대 세 개의 독립 트랜잭션으로 시도한다. */
	public void reconcileRoom(Long roomId, Instant requestTime) {
		Objects.requireNonNull(roomId, "roomId");
		Objects.requireNonNull(requestTime, "requestTime");
		executeWithRetry(
			() -> {
				executor.reconcileRoom(roomId, requestTime);
				return 0;
			},
			ignoredAttempt -> {}, roomId);
	}

	/** 목록·내 모임 필터와 페이지 계산 전에 due 방 전체를 보정한다. */
	public int reconcileDueRooms(Instant requestTime) {
		return reconcileDueRooms(requestTime, ignoredAttempt -> {});
	}

	/** 스케줄러처럼 재시도 전 지연이 필요한 호출자만 시도별 지연을 주입한다. */
	int reconcileDueRooms(Instant requestTime, IntConsumer beforeRetry) {
		Objects.requireNonNull(requestTime, "requestTime");
		Objects.requireNonNull(beforeRetry, "beforeRetry");
		return executeWithRetry(() -> executor.reconcileDueRooms(requestTime), beforeRetry, null);
	}

	private int executeWithRetry(
		IntSupplier reconciliationAttempt, IntConsumer beforeRetry, Long roomId) {
		RuntimeException lastConflict = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return reconciliationAttempt.getAsInt();
			} catch (OptimisticLockException exception) {
				lastConflict = exception;
			} catch (ObjectOptimisticLockingFailureException exception) {
				lastConflict = exception;
			}
			if (attempt < MAX_ATTEMPTS) {
				int nextAttempt = attempt + 1;
				beforeRetry.accept(nextAttempt);
				logRetry(roomId, nextAttempt, false);
			}
		}

		logRetry(roomId, MAX_ATTEMPTS, true);
		throw new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION, lastConflict);
	}

	private void logRetry(Long roomId, int attempt, boolean exhausted) {
		if (roomId == null) {
			if (exhausted) {
				log.warn("event=room_state_reconciliation_retry attempt={}", attempt);
			} else {
				log.debug("event=room_state_reconciliation_retry attempt={}", attempt);
			}
			return;
		}
		if (exhausted) {
			log.warn("event=room_state_reconciliation_retry roomId={} attempt={}", roomId, attempt);
		} else {
			log.debug("event=room_state_reconciliation_retry roomId={} attempt={}", roomId, attempt);
		}
	}
}
