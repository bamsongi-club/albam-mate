package cloud.bamsongi.albammate.room.service.command;

import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 현재 WAITING 대기 관계 하나를 조건부로 취소한다. */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class RoomWaitlistCancelExecutor {

	@NonNull private final RoomRepository roomRepository;
	@NonNull private final RoomWaitlistRepository roomWaitlistRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void cancel(long currentUserId, long roomId, Instant requestTime) {
		Objects.requireNonNull(requestTime, "requestTime");
		Room room = roomRepository.findById(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		room.reconcileStateAt(requestTime);
		if (roomWaitlistRepository.cancelWaiting(roomId, currentUserId, requestTime) == 0) {
			throw new BusinessException(ErrorCode.WAITLIST_ENTRY_NOT_FOUND);
		}
	}
}
