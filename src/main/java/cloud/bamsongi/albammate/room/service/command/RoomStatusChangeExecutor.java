package cloud.bamsongi.albammate.room.service.command;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.RoomCanceledEvent;
import cloud.bamsongi.albammate.room.contract.RoomChangeEventRecorder;
import cloud.bamsongi.albammate.room.contract.RoomTerminalStateReached;
import cloud.bamsongi.albammate.room.dto.RoomStatusResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** 방 취소·종료 한 번을 상태 보정과 함께 독립된 쓰기 트랜잭션에서 실행한다. */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class RoomStatusChangeExecutor {

	private final RoomRepository roomRepository;
	private final RoomWaitlistRepository roomWaitlistRepository;
	private final ParticipationRepository participationRepository;
	private final RoomChangeEventRecorder roomChangeEventRecorder;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RoomStatusResponse cancelRoom(long currentUserId, long roomId, Instant requestTime) {
		Room room = findHostedRoom(currentUserId, roomId);
		room.reconcileStateAt(requestTime);
		if (!room.cancel()) {
			throw new BusinessException(ErrorCode.INVALID_ROOM_STATUS_TRANSITION);
		}
		roomRepository.save(room);
		roomRepository.flush();
		roomWaitlistRepository.cancelAllWaiting(room.getId(), requestTime);
		var recipientUserIds = participationRepository.findUserIdsByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			room.getId(), ParticipationStatus.ACTIVE);
		if (!recipientUserIds.isEmpty()) {
			roomChangeEventRecorder.record(new RoomCanceledEvent(room.getId(), requestTime), recipientUserIds);
		}
		eventPublisher.publishEvent(new RoomTerminalStateReached(room.getId(), requestTime));
		return RoomStatusResponse.from(room);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RoomStatusResponse finishRoom(long currentUserId, long roomId, Instant requestTime) {
		Room room = findHostedRoom(currentUserId, roomId);
		RoomStatus statusBeforeReconciliation = room.getStatus();
		room.reconcileStateAt(requestTime);
		if (room.getStatus() == RoomStatus.FINISHED) {
			if (statusBeforeReconciliation != RoomStatus.FINISHED) {
				roomRepository.flush();
				expireWaitingAndPublishTerminalEvent(room, requestTime);
			}
			return RoomStatusResponse.from(room);
		}
		if (!room.finishAt(requestTime)) {
			throw new BusinessException(ErrorCode.INVALID_ROOM_STATUS_TRANSITION);
		}
		roomRepository.flush();
		expireWaitingAndPublishTerminalEvent(room, requestTime);
		return RoomStatusResponse.from(room);
	}

	private void expireWaitingAndPublishTerminalEvent(Room room, Instant requestTime) {
		roomWaitlistRepository.expireAllWaiting(room.getId(), requestTime);
		eventPublisher.publishEvent(new RoomTerminalStateReached(room.getId(), requestTime));
	}

	private Room findHostedRoom(long currentUserId, long roomId) {
		Room room = roomRepository
			.findById(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		if (room.getHostUserId() != currentUserId) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		return room;
	}

}
