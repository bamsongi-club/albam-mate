package cloud.bamsongi.albammate.room.service.query;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.MyRoomRole;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/** 상태 보정이 커밋된 뒤 내 모임 목록을 독립 읽기 트랜잭션으로 읽는다. */
@Service
class MyRoomReadService {

	private final RoomRepository roomRepository;

	public MyRoomReadService(RoomRepository roomRepository) {
		this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
	public MyRoomReadResult findMyRoomsAt(
		Long currentUserId, MyRoomRole role, Pageable pageable, Instant requestTime) {
		Objects.requireNonNull(currentUserId, "currentUserId");
		Objects.requireNonNull(role, "role");
		Objects.requireNonNull(pageable, "pageable");

		Page<Room> rooms = roomRepository.findMyRoomsAt(
			currentUserId,
			role != MyRoomRole.JOINED,
			role != MyRoomRole.HOSTED,
			pageable);
		Map<Long, RoomStatus> effectiveStatuses = rooms.getContent().stream().collect(Collectors.toUnmodifiableMap(
			Room::getId, room -> RoomEffectiveStatus.resolve(room, requestTime)));
		return new MyRoomReadResult(rooms, effectiveStatuses, requestTime);
	}

	public record MyRoomReadResult(Page<Room> rooms, Map<Long, RoomStatus> effectiveStatuses, Instant requestTime) {

		public RoomStatus effectiveStatusFor(Room room) {
			return effectiveStatuses.get(room.getId());
		}
	}

	public Page<Room> findMyRooms(Long currentUserId, MyRoomRole role, Pageable pageable) {
		return roomRepository.findMyRooms(currentUserId, role != MyRoomRole.JOINED, role != MyRoomRole.HOSTED,
			pageable);
	}
}
