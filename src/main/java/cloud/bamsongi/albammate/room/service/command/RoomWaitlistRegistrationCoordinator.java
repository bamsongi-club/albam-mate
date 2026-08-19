package cloud.bamsongi.albammate.room.service.command;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier.BoundedFullJitter;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier.RetryDelay;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/** PART-04 대기 활성화의 두 충돌 원인을 하나의 세 번 예산으로 조정한다. */
@Service
@Slf4j
class RoomWaitlistRegistrationCoordinator {

	private static final int MAX_ATTEMPTS = 3;
	private static final String WAITING_QUEUE_ORDER_CONSTRAINT = "uq_room_waitlists_waiting_room_queue_order";
	private static final String USE_CASE = "ROOM_WAITLIST_REGISTRATION";
	private static final String WAITING_QUEUE_ORDER_CONFLICT = "WAITING_QUEUE_ORDER_CONFLICT";
	private static final String WAITING_QUEUE_ORDER_CONFLICT_EXHAUSTED = "WAITING_QUEUE_ORDER_CONFLICT_EXHAUSTED";
	private static final String UNEXPECTED_INTEGRITY_FAILURE = "UNEXPECTED_INTEGRITY_FAILURE";
	private static final String UNEXPECTED_DATABASE_FAILURE = "UNEXPECTED_DATABASE_FAILURE";

	@NonNull private final Clock clock;
	@NonNull private final RoomWaitlistRegistrationExecutor executor;
	private final BoundedFullJitter boundedFullJitter;

	RoomWaitlistRegistrationCoordinator(
		Clock clock, RoomWaitlistRegistrationExecutor executor, @Nullable BoundedFullJitter boundedFullJitter) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.executor = Objects.requireNonNull(executor, "executor");
		this.boundedFullJitter = boundedFullJitter == null ? new BoundedFullJitter() : boundedFullJitter;
	}

	RoomWaitlistCommandService.RegistrationResult register(long currentUserId, long roomId) {
		Instant requestTime = Instant.now(clock);
		long requestSeed = boundedFullJitter.nextRequestSeed();
		RuntimeException lastRetryableFailure = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return executor.register(currentUserId, roomId, requestTime);
			} catch (ObjectOptimisticLockingFailureException exception) {
				lastRetryableFailure = exception;
				waitBeforeRetry(attempt, roomId, requestSeed);
			} catch (DataIntegrityViolationException exception) {
				if (!isWaitingQueueOrderConflict(exception)) {
					logUnexpectedIntegrityFailure(roomId, attempt);
					throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, exception);
				}
				lastRetryableFailure = exception;
				if (attempt < MAX_ATTEMPTS) {
					waitBeforeRetry(attempt, roomId, requestSeed);
					logWaitingQueueOrderRetry(roomId, attempt + 1);
				}
			} catch (DataAccessException exception) {
				logUnexpectedDatabaseFailure(roomId);
				throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, exception);
			}
		}

		if (lastRetryableFailure instanceof ObjectOptimisticLockingFailureException) {
			throw new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION, lastRetryableFailure);
		}
		logWaitingQueueOrderConflictExhausted(roomId, MAX_ATTEMPTS);
		throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, lastRetryableFailure);
	}

	private void waitBeforeRetry(int attempt, long roomId, long requestSeed) {
		if (attempt >= MAX_ATTEMPTS) {
			return;
		}
		int nextAttempt = attempt + 1;
		RetryDelay retryDelay = boundedFullJitter.calculate(
			requestSeed, "room_waitlist_registration_retry", roomId, nextAttempt);
		boundedFullJitter.waitBeforeRetry(retryDelay);
		log.trace("event={} roomId={} attempt={} requestSeed={} jitterSeed={} maxDelayMillis={} delayMillis={}",
			"room_waitlist_registration_retry",
			roomId,
			nextAttempt,
			retryDelay.requestSeed(),
			retryDelay.seed(),
			retryDelay.maxDelayMillis(),
			retryDelay.delayMillis());
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

	private void logUnexpectedDatabaseFailure(long roomId) {
		log.error("roomId={} useCase={} reasonCode={}", roomId, USE_CASE, UNEXPECTED_DATABASE_FAILURE);
	}
}
