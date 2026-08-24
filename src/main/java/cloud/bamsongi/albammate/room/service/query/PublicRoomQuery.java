package cloud.bamsongi.albammate.room.service.query;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/** {@link RoomListSearchCriteria}를 공개 목록 JPQL 파라미터로 변환하는 room 모듈 내부 전용 어댑터다. */
@Component
class PublicRoomQuery {

	private static final Set<RoomStatus> PUBLIC_STATUSES = Set.of(RoomStatus.RECRUITING, RoomStatus.CLOSED);

	private final RoomRepository roomRepository;

	PublicRoomQuery(RoomRepository roomRepository) {
		this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
	}

	/** 주입받은 {@code requestTime}을 새로 생성하지 않고 그대로 조회 조건과 유효 종료 경계에 사용한다. */
	Page<Room> findPublicRooms(RoomListSearchCriteria criteria, Pageable pageable, Instant requestTime) {
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
}
