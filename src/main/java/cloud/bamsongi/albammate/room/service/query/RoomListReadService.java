package cloud.bamsongi.albammate.room.service.query;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 고정 요청시각의 유효 상태로 공개 목록을 읽는 독립 읽기 트랜잭션이다. */
@Service
@RequiredArgsConstructor
class RoomListReadService {

	private static final Set<RoomStatus> PUBLIC_STATUSES = Set.of(RoomStatus.RECRUITING, RoomStatus.CLOSED);

	@NonNull private final RoomRepository roomRepository;
	@NonNull private final RoomWaitlistRepository roomWaitlistRepository;

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
	public RoomListReadResult findPublicRoomsAt(
		RoomListSearchCriteria criteria, Pageable pageable, Long currentUserId, Instant requestTime) {
		Page<Room> rooms = findFilteredPublicRooms(criteria, pageable, requestTime);
		Set<Long> activeParticipationRoomIds = findActiveParticipationRoomIds(currentUserId, rooms);
		Set<Long> waitingRoomIds = findWaitingRoomIds(currentUserId, rooms);
		Map<Long, RoomStatus> effectiveStatuses = rooms.getContent().stream().collect(Collectors.toUnmodifiableMap(
			Room::getId, room -> RoomEffectiveStatus.resolve(room, requestTime)));
		return new RoomListReadResult(
			rooms, effectiveStatuses, activeParticipationRoomIds, waitingRoomIds, requestTime);
	}

	private Page<Room> findFilteredPublicRooms(
		RoomListSearchCriteria criteria, Pageable pageable, Instant requestTime) {
		return roomRepository.findPublicRoomsAt(
			criteria.roomType(),
			criteria.status() != null,
			criteria.status() == RoomStatus.RECRUITING,
			criteria.status() == RoomStatus.CLOSED,
			requestTime,
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
			requestTime.minus(Room.AUTOMATIC_FINISH_AFTER_START),
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
		Page<Room> rooms,
		Map<Long, RoomStatus> effectiveStatuses,
		Set<Long> activeParticipationRoomIds,
		Set<Long> waitingRoomIds,
		Instant requestTime) {

		public RoomStatus effectiveStatusFor(Room room) {
			return effectiveStatuses.get(room.getId());
		}
	}

}
