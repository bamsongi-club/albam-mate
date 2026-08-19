package cloud.bamsongi.albammate.room.service.command;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** PART-04 대기 활성화의 두 충돌 원인을 하나의 세 번 예산으로 조정한다. */
@Service
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class RoomWaitlistRegistrationCoordinator {

	private static final int MAX_ATTEMPTS = 3;
	private static final String WAITING_QUEUE_ORDER_CONSTRAINT = "uq_room_waitlists_waiting_room_queue_order";
	private static final String USE_CASE = "ROOM_WAITLIST_REGISTRATION";
	private static final String WAITING_QUEUE_ORDER_CONFLICT = "WAITING_QUEUE_ORDER_CONFLICT";
	private static final String WAITING_QUEUE_ORDER_CONFLICT_EXHAUSTED = "WAITING_QUEUE_ORDER_CONFLICT_EXHAUSTED";
	private static final String UNEXPECTED_INTEGRITY_FAILURE = "UNEXPECTED_INTEGRITY_FAILURE";

	@NonNull private final Clock clock;
	@NonNull private final RoomWaitlistRegistrationExecutor executor;

	RoomWaitlistCommandService.RegistrationResult register(long currentUserId, long roomId) {
		Instant requestTime = Instant.now(clock);
		RuntimeException lastRetryableFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return executor.register(currentUserId, roomId, requestTime);
			} catch (ObjectOptimisticLockingFailureException exception) {
				lastRetryableFailure = exception;
			} catch (DataIntegrityViolationException exception) {
				if (!isWaitingQueueOrderConflict(exception)) {
					logUnexpectedIntegrityFailure(roomId, attempt);
					throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, exception);
				}
				lastRetryableFailure = exception;
				if (attempt < MAX_ATTEMPTS) {
					logWaitingQueueOrderRetry(roomId, attempt + 1);
				}
			} catch (DataAccessException exception) {
				logTechnicalFailure(roomId, attempt, resolveTechnicalReason(exception), resolveSqlState(exception));
				throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, exception);
			} catch (BusinessException exception) {
				throw exception;
			} catch (RuntimeException exception) {
				logTechnicalFailure(roomId, attempt, "UNEXPECTED_TECHNICAL_FAILURE", resolveSqlState(exception));
				throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, exception);
			}
		}

		if (lastRetryableFailure instanceof ObjectOptimisticLockingFailureException) {
			throw new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION, lastRetryableFailure);
		}
		logWaitingQueueOrderConflictExhausted(roomId, MAX_ATTEMPTS);
		throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, lastRetryableFailure);
	}

	private boolean isWaitingQueueOrderConflict(DataIntegrityViolationException exception) {
		Throwable cause = exception;
		while (cause != null) {
			if (cause instanceof ConstraintViolationException constraintViolationException
				&& WAITING_QUEUE_ORDER_CONSTRAINT.equals(constraintViolationException.getConstraintName())) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}

	private void logWaitingQueueOrderRetry(long roomId, int nextAttempt) {
		log.warn("roomId={} useCase={} attempt={} reasonCode={}",
			roomId, USE_CASE, nextAttempt, WAITING_QUEUE_ORDER_CONFLICT);
	}

	private void logWaitingQueueOrderConflictExhausted(long roomId, int attempt) {
		log.error("roomId={} useCase={} attempt={} reasonCode={}",
			roomId, USE_CASE, attempt, WAITING_QUEUE_ORDER_CONFLICT_EXHAUSTED);
	}

	private void logUnexpectedIntegrityFailure(long roomId, int attempt) {
		log.error("roomId={} useCase={} attempt={} reasonCode={}",
			roomId, USE_CASE, attempt, UNEXPECTED_INTEGRITY_FAILURE);
	}

	/**
	 * 기술 실패 로그만 {@code sqlState}로 끝난다. 원인 사슬에 {@link SQLException}이 없거나 SQLSTATE가 비어 있으면
	 * 빈 값으로 남겨 재시도·소진·정합성 로그의 {@code reasonCode} 종료 형식과 구분한다.
	 */
	private void logTechnicalFailure(long roomId, int attempt, String reasonCode, String sqlState) {
		log.error("roomId={} useCase={} attempt={} reasonCode={} sqlState={}",
			roomId, USE_CASE, attempt, reasonCode, sqlState);
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
				return "LOCK_TIMEOUT";
			}
			cause = cause.getCause();
		}
		return "UNEXPECTED_TECHNICAL_FAILURE";
	}
}
