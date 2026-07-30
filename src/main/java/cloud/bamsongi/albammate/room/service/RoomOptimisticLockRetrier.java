package cloud.bamsongi.albammate.room.service;

import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;

/** 트랜잭션 경계 밖에서 ROOM 낙관 락 충돌만 제한적으로 재시도한다. */
@Service
@Slf4j
public class RoomOptimisticLockRetrier {

	private static final int MAX_ATTEMPTS = 3;

	/** 지연 없이 낙관 락 충돌만 최대 세 번 시도한다. */
	public <T> T execute(Supplier<T> attempt, String event, Long roomId) {
		return execute(attempt, event, roomId, ignoredAttempt -> {});
	}

	/** 재시도 전 작업이 필요한 호출자만 다음 시도 직전에 hook을 실행한다. */
	public <T> T execute(Supplier<T> attempt, String event, Long roomId, IntConsumer beforeRetry) {
		Objects.requireNonNull(attempt, "attempt");
		Objects.requireNonNull(event, "event");
		Objects.requireNonNull(beforeRetry, "beforeRetry");

		RuntimeException lastConflict = null;
		for (int attemptNumber = 1; attemptNumber <= MAX_ATTEMPTS; attemptNumber++) {
			try {
				return attempt.get();
			} catch (OptimisticLockException | ObjectOptimisticLockingFailureException exception) {
				lastConflict = exception;
			}
			if (attemptNumber < MAX_ATTEMPTS) {
				int nextAttempt = attemptNumber + 1;
				beforeRetry.accept(nextAttempt);
				logRetry(event, roomId, nextAttempt, false);
			}
		}

		logRetry(event, roomId, MAX_ATTEMPTS, true);
		throw new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION, lastConflict);
	}

	private void logRetry(String event, Long roomId, int attempt, boolean exhausted) {
		if (roomId == null) {
			if (exhausted) {
				log.warn("event={} attempt={}", event, attempt);
			} else {
				log.debug("event={} attempt={}", event, attempt);
			}
			return;
		}
		if (exhausted) {
			log.warn("event={} roomId={} attempt={}", event, roomId, attempt);
		} else {
			log.debug("event={} roomId={} attempt={}", event, roomId, attempt);
		}
	}
}
