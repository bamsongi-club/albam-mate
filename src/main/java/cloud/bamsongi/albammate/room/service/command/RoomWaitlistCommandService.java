package cloud.bamsongi.albammate.room.service.command;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.room.dto.MyRoomWaitlistResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 대기 등록·재신청과 취소의 Controller-facing 진입점이다. */
@Service
@RequiredArgsConstructor
public class RoomWaitlistCommandService {

	@NonNull private final RoomWaitlistRegistrationCoordinator registrationCoordinator;
	@NonNull private final RoomWaitlistCancelExecutor cancelExecutor;
	@NonNull private final RoomCommandExecutionCoordinator executionCoordinator;

	public RegistrationResult register(long currentUserId, long roomId) {
		return registrationCoordinator.register(currentUserId, roomId);
	}

	public void cancel(long currentUserId, long roomId) {
		executionCoordinator.execute(
			roomId,
			"room_waitlist_cancel_retry",
			requestTime -> {
				cancelExecutor.cancel(currentUserId, roomId, requestTime);
				return null;
			});
	}

	public record RegistrationResult(MyRoomWaitlistResponse response, boolean created) {
	}
}
