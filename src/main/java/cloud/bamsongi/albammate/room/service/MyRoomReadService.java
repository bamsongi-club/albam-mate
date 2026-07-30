package cloud.bamsongi.albammate.room.service;

import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.MyRoomRole;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/** 상태 보정이 커밋된 뒤 내 모임 목록을 독립 읽기 트랜잭션으로 읽는다. */
@Service
public class MyRoomReadService {

	private final RoomRepository roomRepository;

	public MyRoomReadService(RoomRepository roomRepository) {
		this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public Page<Room> findMyRooms(Long currentUserId, MyRoomRole role, Pageable pageable) {
		Objects.requireNonNull(currentUserId, "currentUserId");
		Objects.requireNonNull(role, "role");
		Objects.requireNonNull(pageable, "pageable");

		return roomRepository.findMyRooms(
			currentUserId, role != MyRoomRole.JOINED, role != MyRoomRole.HOSTED, pageable);
	}
}
