package cloud.bamsongi.albammate.room.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomUpdateRequest;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;

/** 방 수정 시 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도한다. */
@Service
@RequiredArgsConstructor
public class RoomUpdateService {

	private static final int MAX_ATTEMPTS = 3;

	private final RoomUpdateExecutor executor;
	private final Clock clock;

	/**
	 * 요청 시작 시각을 한 번 고정하고, 낙관 락 충돌만 최대 세 번의 독립 트랜잭션으로 재시도한다. 세 시도가 모두 충돌하면 {@code
	 * ROOM_CONCURRENT_MODIFICATION}을 반환하며 업무 규칙 오류는 재시도하지 않는다.
	 */
	public ParticipantRoomResponse updateRoom(
		long currentUserId, long roomId, RoomUpdateRequest request) {
		Instant requestTime = Instant.now(clock);
		RuntimeException lastConflict = null;

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return executor.updateRoom(currentUserId, roomId, request, requestTime);
			} catch (OptimisticLockException | ObjectOptimisticLockingFailureException exception) {
				lastConflict = exception;
			}
		}

		throw new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION, lastConflict);
	}
}
