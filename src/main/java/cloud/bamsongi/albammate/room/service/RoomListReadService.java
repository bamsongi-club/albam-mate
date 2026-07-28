package cloud.bamsongi.albammate.room.service;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 요청 경계 상태 보정 후 최신 공개 목록을 읽는 독립 읽기 트랜잭션이다. */
@Service
public class RoomListReadService {

    private static final Set<RoomStatus> PUBLIC_STATUSES =
            Set.of(RoomStatus.RECRUITING, RoomStatus.CLOSED);

    private final RoomRepository roomRepository;

    public RoomListReadService(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public RoomListReadResult findPublicRooms(
            RoomType roomType, Long gameId, String keyword, Pageable pageable, Long currentUserId) {
        Page<Room> rooms =
                keyword == null
                        ? roomRepository.findPublicRoomsWithoutKeyword(
                                roomType, gameId, PUBLIC_STATUSES, pageable)
                        : roomRepository.findPublicRoomsByTitleContainingIgnoreCase(
                                roomType, gameId, keyword, PUBLIC_STATUSES, pageable);
        Set<Long> activeParticipationRoomIds =
                currentUserId == null || rooms.isEmpty()
                        ? Set.of()
                        : Set.copyOf(
                                roomRepository.findActiveParticipationRoomIds(
                                        currentUserId,
                                        rooms.getContent().stream().map(Room::getId).toList()));
        return new RoomListReadResult(rooms, activeParticipationRoomIds);
    }

    public record RoomListReadResult(Page<Room> rooms, Set<Long> activeParticipationRoomIds) {}
}
