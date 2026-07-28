package cloud.bamsongi.albammate.room.service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomStatusResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 방 취소·종료 한 번을 상태 보정과 함께 독립된 쓰기 트랜잭션에서 실행한다. */
@Service
class RoomStatusChangeExecutor {

    private final RoomRepository roomRepository;

    RoomStatusChangeExecutor(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoomStatusResponse cancelRoom(long currentUserId, long roomId, Instant requestTime) {
        Room room = findHostedRoom(currentUserId, roomId);
        room.reconcileStateAt(requestTime);
        if (!room.cancel()) {
            throw new BusinessException(ErrorCode.INVALID_ROOM_STATUS_TRANSITION);
        }
        return response(room);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoomStatusResponse finishRoom(long currentUserId, long roomId, Instant requestTime) {
        Room room = findHostedRoom(currentUserId, roomId);
        room.reconcileStateAt(requestTime);
        if (!room.finishAt(requestTime)) {
            throw new BusinessException(ErrorCode.INVALID_ROOM_STATUS_TRANSITION);
        }
        return response(room);
    }

    private Room findHostedRoom(long currentUserId, long roomId) {
        Room room =
                roomRepository
                        .findById(roomId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        if (room.getHostUserId() != currentUserId) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return room;
    }

    private RoomStatusResponse response(Room room) {
        return new RoomStatusResponse(room.getId(), room.getStatus());
    }
}
