package cloud.bamsongi.albammate.room.service;

import java.sql.SQLException;
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
			} catch (BusinessException exception) {
				throw exception;
			} catch (RuntimeException exception) {
				logTechnicalFailure(event, roomId, attemptNumber, exception);
				throw exception;
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

	private void logTechnicalFailure(String event, Long roomId, int attempt, RuntimeException exception) {
		String reasonCode = resolveTechnicalReason(exception);
		String sqlState = resolveSqlState(exception);
		String useCase = resolveUseCase(event);
		if (roomId == null) {
			log.error("event={} attempt={} useCase={} reasonCode={} sqlState={}", event, attempt, useCase, reasonCode,
				sqlState);
			return;
		}
		log.error("event={} roomId={} attempt={} useCase={} reasonCode={} sqlState={}", event, roomId, attempt,
			useCase, reasonCode, sqlState);
	}

	private String resolveSqlState(Throwable exception) {
		Throwable cause = exception;
		while (cause != null) {
			if (cause instanceof SQLException sqlException) {
				return Objects.requireNonNullElse(sqlException.getSQLState(), "");
			}
			cause = cause.getCause();
		}
		return "";
	}

	private String resolveTechnicalReason(Throwable exception) {
		boolean lockTimeoutType = false;
		Throwable cause = exception;
		while (cause != null) {
			if (cause instanceof SQLException sqlException) {
				if ("40P01".equals(sqlException.getSQLState())) {
					return "DEADLOCK";
				}
				if ("55P03".equals(sqlException.getSQLState())) {
					return "LOCK_TIMEOUT";
				}
			}
			String simpleName = cause.getClass().getSimpleName();
			if (simpleName.contains("Deadlock")) {
				return "DEADLOCK";
			}
			if (simpleName.contains("LockTimeout") || simpleName.contains("PessimisticLock")) {
				lockTimeoutType = true;
			}
			cause = cause.getCause();
		}
		return lockTimeoutType ? "LOCK_TIMEOUT" : "UNEXPECTED_TECHNICAL_FAILURE";
	}

	private void logRetry(String event, Long roomId, int attempt, boolean exhausted) {
		String useCase = resolveUseCase(event);
		String reasonCode = exhausted ? "OPTIMISTIC_LOCK_EXHAUSTED" : "OPTIMISTIC_LOCK_CONFLICT";
		if (roomId == null) {
			if (exhausted) {
				log.atWarn().addKeyValue("event", event).addKeyValue("attempt", attempt)
					.addKeyValue("useCase", useCase).addKeyValue("reasonCode", reasonCode).log("room retry exhausted");
			} else {
				log.atDebug().addKeyValue("event", event).addKeyValue("attempt", attempt)
					.addKeyValue("useCase", useCase).addKeyValue("reasonCode", reasonCode).log("room retry conflict");
			}
			return;
		}
		if (exhausted) {
			log.atWarn().addKeyValue("event", event).addKeyValue("roomId", roomId).addKeyValue("attempt", attempt)
				.addKeyValue("useCase", useCase).addKeyValue("reasonCode", reasonCode).log("room retry exhausted");
		} else {
			log.atDebug().addKeyValue("event", event).addKeyValue("roomId", roomId).addKeyValue("attempt", attempt)
				.addKeyValue("useCase", useCase).addKeyValue("reasonCode", reasonCode).log("room retry conflict");
		}
	}

	private String resolveUseCase(String event) {
		return switch (event) {
			case "room_update_retry" -> "ROOM_UPDATE";
			case "room_cancel_retry" -> "ROOM_CANCEL";
			case "room_finish_retry" -> "ROOM_FINISH";
			case "room_participation_retry" -> "ROOM_PARTICIPATION";
			case "room_participation_cancel_retry" -> "ROOM_PARTICIPATION_CANCEL";
			case "room_waitlist_cancel_retry" -> "ROOM_WAITLIST_CANCEL";
			case "room_state_reconciliation_retry" -> "ROOM_STATUS_CORRECTION";
			default -> "ROOM_STATUS_CORRECTION";
		};
	}
}
