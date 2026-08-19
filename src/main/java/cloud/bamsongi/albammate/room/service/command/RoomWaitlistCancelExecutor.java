package cloud.bamsongi.albammate.room.service.command;

import java.time.Instant;
import java.util.Objects;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistStateProjection;

/** 현재 WAITING 대기 관계 하나를 조건부로 취소한다. */
@Service
class RoomWaitlistCancelExecutor {

	private final RoomRepository roomRepository;
	private final RoomWaitlistRepository roomWaitlistRepository;
	private final boolean postgresDatabase;

	RoomWaitlistCancelExecutor(
		RoomRepository roomRepository,
		RoomWaitlistRepository roomWaitlistRepository,
		Environment environment) {
		this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
		this.roomWaitlistRepository = Objects.requireNonNull(roomWaitlistRepository, "roomWaitlistRepository");
		Objects.requireNonNull(environment, "environment");
		String jdbcUrl = environment.getProperty("spring.datasource.url", "");
		this.postgresDatabase = !jdbcUrl.startsWith("jdbc:h2:");
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void cancel(long currentUserId, long roomId, Instant requestTime) {
		Objects.requireNonNull(requestTime, "requestTime");
		setLocalWriteLockTimeout();
		Room room = roomRepository.findByIdForWrite(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		room.reconcileStateAt(requestTime);
		RoomWaitlistStateProjection waiting = roomWaitlistRepository
			.findStateWithPositionByRoomIdAndUserId(roomId, currentUserId)
			.filter(state -> state.getStatus() == RoomWaitlistStatus.WAITING)
			.orElseThrow(() -> new BusinessException(ErrorCode.WAITLIST_ENTRY_NOT_FOUND));
		if (roomWaitlistRepository.cancelWaiting(
			roomId, currentUserId, waiting.getQueueOrder(), requestTime) == 0) {
			throw new BusinessException(ErrorCode.WAITLIST_ENTRY_NOT_FOUND);
		}
	}

	private void setLocalWriteLockTimeout() {
		if (postgresDatabase) {
			roomRepository.setLocalWriteLockTimeout();
		}
	}
}
