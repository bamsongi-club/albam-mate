package cloud.bamsongi.albammate.room.service.query;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 상태 보정이 커밋된 뒤 방과 현재 활성 참가 관계를 함께 읽는 독립 읽기 트랜잭션이다. */
@Service
@RequiredArgsConstructor
class RoomDetailReadService {

	@NonNull private final RoomRepository roomRepository;
	@NonNull private final ParticipationRepository participationRepository;
	@NonNull private final RoomWaitlistRepository roomWaitlistRepository;

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
	public RoomDetailReadResult findRoomDetail(Long roomId, Long currentUserId) {
		Room room = roomRepository
			.findById(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		List<Participation> activeParticipations = participationRepository.findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			roomId, ParticipationStatus.ACTIVE);
		boolean currentUserWaiting = shouldReadCurrentUserWaiting(room, activeParticipations, currentUserId)
			&& roomWaitlistRepository
				.findStateWithPositionByRoomIdAndUserId(roomId, currentUserId)
				.map(state -> state.getStatus() == RoomWaitlistStatus.WAITING)
				.orElse(false);
		return new RoomDetailReadResult(room, List.copyOf(activeParticipations), currentUserWaiting);
	}

	private boolean shouldReadCurrentUserWaiting(
		Room room, List<Participation> activeParticipations, Long currentUserId) {
		return currentUserId != null
			&& !room.getHostUserId().equals(currentUserId)
			&& activeParticipations.stream()
				.noneMatch(participation -> participation.getUserId().equals(currentUserId));
	}

	public record RoomDetailReadResult(
		Room room, List<Participation> activeParticipations, boolean currentUserWaiting) {
	}
}
