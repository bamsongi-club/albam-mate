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

/**
 * 상태 보정이 커밋된 뒤 방과 현재 활성 참가 관계를 함께 읽는 독립 {@code REQUIRES_NEW} 읽기 트랜잭션이다.
 *
 * <p>전체 {@code ACTIVE} 참가자 목록은 주최자와 현재 {@code ACTIVE} 참가자에게만 읽고,
 * 그 밖의 요청자에게는 빈 목록을 반환한다.
 */
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
		return new RoomDetailReadResult(
			room,
			List.copyOf(activeParticipations),
			currentUserIsActiveParticipant,
			currentUserWaiting);
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

	/**
	 * 상세 응답 조립에 필요한 같은 스냅샷의 ROOM 사실이다.
	 *
	 * <p>{@code activeParticipations}는 주최자 또는 현재 {@code ACTIVE} 참가자에게만 전체 목록을 담고,
	 * 그 밖의 요청자에게는 빈 목록이다. {@code currentUserIsActiveParticipant}는 목록 포함 여부가 아니라
	 * 요청자 단건 관계 조회로 판정한 현재 {@code ACTIVE} 사실이다.
	 */
	public record RoomDetailReadResult(
		Room room,
		List<Participation> activeParticipations,
		boolean currentUserIsActiveParticipant,
		boolean currentUserWaiting) {
	}
}
