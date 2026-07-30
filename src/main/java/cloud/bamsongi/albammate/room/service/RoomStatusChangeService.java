package cloud.bamsongi.albammate.room.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.room.RoomOptimisticLockRetrier;
import cloud.bamsongi.albammate.room.dto.RoomStatusResponse;
import lombok.RequiredArgsConstructor;

/** 방 취소·종료 시 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도한다. */
@Service
@RequiredArgsConstructor
public class RoomStatusChangeService {

	private final RoomStatusChangeExecutor executor;
	private final Clock clock;
	private final RoomOptimisticLockRetrier retrier;

	/** 요청 시작 시각을 고정해 상태를 보정한 뒤 취소를 시도하고, 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도한다. */
	public RoomStatusResponse cancelRoom(long currentUserId, long roomId) {
		Instant requestTime = Instant.now(clock);
		return retrier.execute(
			() -> executor.cancelRoom(currentUserId, roomId, requestTime),
			"room_cancel_retry", roomId);
	}

	/**
	 * 요청 시작 시각을 고정해 상태를 보정하고 낙관 락 충돌만 최대 세 번 재시도한다. 보정 뒤 이미 {@code FINISHED}인 방은 추가 변경 없이 종료 목표를
	 * 달성한 멱등 성공으로 반환한다.
	 */
	public RoomStatusResponse finishRoom(long currentUserId, long roomId) {
		Instant requestTime = Instant.now(clock);
		return retrier.execute(
			() -> executor.finishRoom(currentUserId, roomId, requestTime),
			"room_finish_retry", roomId);
	}
}
