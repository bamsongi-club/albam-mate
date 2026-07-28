package cloud.bamsongi.albammate.room.service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 상태 보정이 커밋된 뒤 방과 현재 활성 참가 관계를 함께 읽는 독립 읽기 트랜잭션이다. */
@Service
public class RoomDetailReadService {

    private final RoomRepository roomRepository;
    private final ParticipationRepository participationRepository;

    public RoomDetailReadService(
            RoomRepository roomRepository, ParticipationRepository participationRepository) {
        this.roomRepository = roomRepository;
        this.participationRepository = participationRepository;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public RoomDetailReadResult findRoomDetail(Long roomId) {
        Room room =
                roomRepository
                        .findById(roomId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        List<Participation> activeParticipations =
                participationRepository.findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
                        roomId, ParticipationStatus.ACTIVE);
        return new RoomDetailReadResult(room, List.copyOf(activeParticipations));
    }

    public record RoomDetailReadResult(Room room, List<Participation> activeParticipations) {}
}
