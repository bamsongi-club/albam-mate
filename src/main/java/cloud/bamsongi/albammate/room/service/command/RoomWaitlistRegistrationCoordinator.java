package cloud.bamsongi.albammate.room.service.command;

import java.time.Clock;
import java.time.Instant;

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
					logInternalFailure(roomId, currentUserId, exception);
					throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, exception);
				}
				lastRetryableFailure = exception;
			}
		}

		if (lastRetryableFailure instanceof ObjectOptimisticLockingFailureException) {
			throw new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION, lastRetryableFailure);
		}
		logInternalFailure(roomId, currentUserId, lastRetryableFailure);
		throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, lastRetryableFailure);
	}

	private boolean isWaitingQueueOrderConflict(DataIntegrityViolationException exception) {
		Throwable cause = exception;
		while (cause != null) {
			if (cause.getMessage() != null && cause.getMessage().contains(WAITING_QUEUE_ORDER_CONSTRAINT)) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}

	private void logInternalFailure(long roomId, long currentUserId, RuntimeException exception) {
		log.error("event=room_waitlist_registration_failed roomId={} actorUserId={} failureType={}",
			roomId, currentUserId, exception.getClass().getSimpleName());
	}
}
