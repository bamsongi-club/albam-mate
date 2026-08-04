package cloud.bamsongi.albammate.room.service.query;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 요청 경계 상태 보정 후 최신 공개 목록을 읽는 독립 읽기 트랜잭션이다. */
@Service
@RequiredArgsConstructor
class RoomListReadService {

	private static final Set<RoomStatus> PUBLIC_STATUSES = Set.of(RoomStatus.RECRUITING, RoomStatus.CLOSED);

	@NonNull private final RoomRepository roomRepository;
	@NonNull private final RoomWaitlistRepository roomWaitlistRepository;

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
	public RoomListReadResult findPublicRooms(
		RoomListSearchCriteria criteria, Pageable pageable, Long currentUserId) {
		Page<Room> rooms = findFilteredPublicRooms(criteria, pageable);
		Set<Long> activeParticipationRoomIds = findActiveParticipationRoomIds(currentUserId, rooms);
		Set<Long> waitingRoomIds = findWaitingRoomIds(currentUserId, rooms);
		return new RoomListReadResult(rooms, activeParticipationRoomIds, waitingRoomIds);
	}

	private Page<Room> findFilteredPublicRooms(RoomListSearchCriteria criteria, Pageable pageable) {
		return roomRepository.findPublicRooms(
			criteria.roomType(),
			criteria.gameId(),
			criteria.hasKeyword(),
			criteria.keywordOrEmpty(),
			criteria.hasStartsAtFrom(),
			criteria.startsAtFromOrEpoch(),
			criteria.hasStartsAtTo(),
			criteria.startsAtToOrEpoch(),
			criteria.hasMinRemainingSeats(),
			criteria.minRemainingSeatsOrZero(),
			criteria.appliedExperienceLevels(),
			criteria.rulemasterOnly(),
			PUBLIC_STATUSES,
			pageable);
	}

	private Set<Long> findActiveParticipationRoomIds(Long currentUserId, Page<Room> rooms) {
		if (currentUserId == null || rooms.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(roomRepository.findActiveParticipationRoomIds(
			currentUserId, rooms.getContent().stream().map(Room::getId).toList()));
	}

	private Set<Long> findWaitingRoomIds(Long currentUserId, Page<Room> rooms) {
		if (currentUserId == null || rooms.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(roomWaitlistRepository.findWaitingRoomIdsByUserIdAndRoomIds(
			currentUserId, rooms.getContent().stream().map(Room::getId).toList()));
	}

	public record RoomListReadResult(
		Page<Room> rooms, Set<Long> activeParticipationRoomIds, Set<Long> waitingRoomIds) {
	}
}
