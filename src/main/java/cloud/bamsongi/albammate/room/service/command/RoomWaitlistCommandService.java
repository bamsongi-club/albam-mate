package cloud.bamsongi.albammate.room.service.command;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.MyRoomWaitlistResponse;
import io.micrometer.core.instrument.Metrics;

/** 대기 등록·재신청과 취소의 Controller-facing 진입점이다. */
@Service
public class RoomWaitlistCommandService {

	private final RoomWaitlistRegistrationCoordinator registrationCoordinator;
	private final RoomWaitlistCancelExecutor cancelExecutor;
	private final RoomCommandExecutionCoordinator executionCoordinator;
	private final RoomWaitlistMetrics metrics;

	RoomWaitlistCommandService(
		RoomWaitlistRegistrationCoordinator registrationCoordinator,
		RoomWaitlistCancelExecutor cancelExecutor,
		RoomCommandExecutionCoordinator executionCoordinator,
		RoomWaitlistMetrics... metrics) {
		this.registrationCoordinator = java.util.Objects.requireNonNull(registrationCoordinator,
			"registrationCoordinator");
		this.cancelExecutor = java.util.Objects.requireNonNull(cancelExecutor, "cancelExecutor");
		this.executionCoordinator = java.util.Objects.requireNonNull(executionCoordinator, "executionCoordinator");
		this.metrics = metrics.length == 0
			? new RoomWaitlistMetrics(Metrics.globalRegistry)
			: java.util.Objects.requireNonNull(metrics[0], "metrics");
	}

	public RegistrationResult register(long currentUserId, long roomId) {
		try {
			RegistrationResult result = registrationCoordinator.register(currentUserId, roomId);
			metrics.recordJoinAccepted();
			return result;
		} catch (BusinessException exception) {
			if (isTechnicalBusinessFailure(exception)) {
				metrics.recordJoinFailed();
			} else {
				metrics.recordJoinRejected();
			}
			throw exception;
		} catch (RuntimeException exception) {
			metrics.recordJoinFailed();
			throw exception;
		}
	}

	public void cancel(long currentUserId, long roomId) {
		try {
			executionCoordinator.execute(
				roomId,
				"room_waitlist_cancel_retry",
				requestTime -> {
					cancelExecutor.cancel(currentUserId, roomId, requestTime);
					return null;
				});
			metrics.recordCancelAccepted();
		} catch (BusinessException exception) {
			if (isTechnicalBusinessFailure(exception)) {
				metrics.recordCancelFailed();
			} else {
				metrics.recordCancelRejected();
			}
			throw exception;
		} catch (RuntimeException exception) {
			metrics.recordCancelFailed();
			throw exception;
		}
	}

	private boolean isTechnicalBusinessFailure(BusinessException exception) {
		return exception.getErrorCode() == ErrorCode.INTERNAL_SERVER_ERROR
			|| exception.getErrorCode() == ErrorCode.ROOM_CONCURRENT_MODIFICATION;
	}

	public record RegistrationResult(MyRoomWaitlistResponse response, boolean created) {
	}
}
