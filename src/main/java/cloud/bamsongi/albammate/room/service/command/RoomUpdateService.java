package cloud.bamsongi.albammate.room.service.command;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomUpdateRequest;
import lombok.RequiredArgsConstructor;

/** 방 수정 시 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도한다. */
@Service
@RequiredArgsConstructor
public class RoomUpdateService {

	private final RoomUpdateExecutor executor;
	private final RoomCommandExecutionCoordinator executionCoordinator;

	/**
	 * 요청 시작 시각을 한 번 고정하고, 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도한다. 세 시도가 모두 충돌하면 {@code
	 * ROOM_CONCURRENT_MODIFICATION}을 반환하며 업무 규칙 오류는 재시도하지 않는다.
	 */
	public ParticipantRoomResponse updateRoom(
		long currentUserId, long roomId, RoomUpdateRequest request) {
		return executionCoordinator.execute(
			roomId,
			"room_update_retry",
			requestTime -> executor.updateRoom(currentUserId, roomId, request, requestTime));
	}
}
