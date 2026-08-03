package cloud.bamsongi.albammate.room.service.query;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;
import lombok.RequiredArgsConstructor;

/** 채팅 모듈이 현재 ROOM 관계를 확인할 때 사용하는 room 쪽 공개 계약 구현이다. */
@Service
@RequiredArgsConstructor
public class RoomChatAccessGuard implements ChatAccessGuard {

	private final RoomRepository roomRepository;
	private final ParticipationRepository participationRepository;
	private final RoomStatusCorrectionCoordinator statusCorrectionCoordinator;
	private final Clock clock;

	@Override
	@Transactional
	public <T> T executeWithAccess(long currentUserId, long roomId, Supplier<T> chatOperation) {
		Objects.requireNonNull(chatOperation, "chatOperation");
		Instant requestTime = Instant.now(clock);
		statusCorrectionCoordinator.correctRoom(roomId, requestTime);
		Room room = roomRepository
			.findByIdForChatAccess(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		if (!isChatAvailable(room)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		if (room.getHostUserId() != currentUserId && !isActiveParticipant(roomId, currentUserId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		return chatOperation.get();
	}

	private boolean isActiveParticipant(long roomId, long currentUserId) {
		return participationRepository
			.findByRoomIdAndUserId(roomId, currentUserId)
			.map(participation -> participation.getStatus() == ParticipationStatus.ACTIVE)
			.orElse(false);
	}

	private boolean isChatAvailable(Room room) {
		return room.getStatus() == RoomStatus.RECRUITING || room.getStatus() == RoomStatus.CLOSED;
	}
}
