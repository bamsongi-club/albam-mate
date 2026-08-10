package cloud.bamsongi.albammate.room.service.query;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
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
		boolean currentUserIsHost = currentUserId != null && room.getHostUserId().equals(currentUserId);
		Optional<Participation> currentUserParticipation = findCurrentUserParticipation(
			roomId, currentUserId, currentUserIsHost);
		boolean currentUserIsActiveParticipant = currentUserParticipation
			.map(Participation::getStatus)
			.filter(ParticipationStatus.ACTIVE::equals)
			.isPresent();
		List<Participation> activeParticipations = shouldReadActiveParticipations(
			currentUserIsHost, currentUserIsActiveParticipant)
				? participationRepository.findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(roomId,
					ParticipationStatus.ACTIVE)
				: List.of();
		boolean currentUserWaiting = shouldReadCurrentUserWaiting(
			room, currentUserId, currentUserIsHost, currentUserIsActiveParticipant)
			&& roomWaitlistRepository
				.findWaitingRoomIdsByUserIdAndRoomIds(currentUserId, List.of(roomId))
				.contains(roomId);
		return new RoomDetailReadResult(room, List.copyOf(activeParticipations), currentUserWaiting);
	}

	private Optional<Participation> findCurrentUserParticipation(
		Long roomId, Long currentUserId, boolean currentUserIsHost) {
		if (currentUserId == null || currentUserIsHost) {
			return Optional.empty();
		}
		return participationRepository.findByRoomIdAndUserId(roomId, currentUserId);
	}

	private boolean shouldReadActiveParticipations(boolean currentUserIsHost, boolean currentUserIsActiveParticipant) {
		return currentUserIsHost || currentUserIsActiveParticipant;
	}

	private boolean shouldReadCurrentUserWaiting(
		Room room,
		Long currentUserId,
		boolean currentUserIsHost,
		boolean currentUserIsActiveParticipant) {
		return currentUserId != null
			&& !currentUserIsHost
			&& !currentUserIsActiveParticipant
			&& !isFinal(room.getStatus());
	}

	private boolean isFinal(RoomStatus status) {
		return status == RoomStatus.CANCELED || status == RoomStatus.FINISHED;
	}

	public record RoomDetailReadResult(
		Room room, List<Participation> activeParticipations, boolean currentUserWaiting) {
	}
}
