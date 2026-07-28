package cloud.bamsongi.albammate.room.service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.RoomStateReconciliationCoordinator;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomParticipationService {

    private final RoomRepository roomRepository;
    private final ParticipationRepository participationRepository;
    private final RoomStateReconciliationCoordinator roomStateReconciliationCoordinator;
    private final Clock clock;

    public RoomParticipationService(
            RoomRepository roomRepository,
            ParticipationRepository participationRepository,
            RoomStateReconciliationCoordinator roomStateReconciliationCoordinator,
            Clock clock) {
        this.roomRepository = roomRepository;
        this.participationRepository = participationRepository;
        this.roomStateReconciliationCoordinator = roomStateReconciliationCoordinator;
        this.clock = clock;
    }

    /** 방 상태를 요청 시각으로 보정한 뒤 신규 또는 취소된 참가 관계를 활성화한다. */
    @Transactional
    public RoomParticipationResponse participate(long currentUserId, long roomId) {
        Instant now = Instant.now(clock);
        roomStateReconciliationCoordinator.reconcileRoom(roomId, now);

        Room room =
                roomRepository
                        .findById(roomId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
        Optional<Participation> existingParticipation =
                participationRepository.findByRoomIdAndUserId(roomId, currentUserId);

        validateParticipation(room, currentUserId, existingParticipation, now);

        Participation participation =
                existingParticipation
                        .map(
                                existing -> {
                                    existing.reactivate(now);
                                    return existing;
                                })
                        .orElseGet(() -> Participation.createActive(room, currentUserId, now));
        room.addActiveParticipant();

        participationRepository.save(participation);
        roomRepository.save(room);
        return toResponse(room);
    }

    private void validateParticipation(
            Room room,
            long currentUserId,
            Optional<Participation> existingParticipation,
            Instant now) {
        if (room.getStatus() == RoomStatus.CANCELED || room.getStatus() == RoomStatus.FINISHED) {
            throw new BusinessException(ErrorCode.ROOM_NOT_RECRUITING);
        }
        if (room.getHostUserId() == currentUserId
                || existingParticipation
                        .map(Participation::getStatus)
                        .filter(ParticipationStatus.ACTIVE::equals)
                        .isPresent()) {
            throw new BusinessException(ErrorCode.ALREADY_PARTICIPATING);
        }
        if (room.getActiveParticipantCount() >= room.getCapacity()) {
            throw new BusinessException(ErrorCode.CAPACITY_EXCEEDED);
        }
        if (!now.isBefore(room.getStartAt()) || room.getStatus() != RoomStatus.RECRUITING) {
            throw new BusinessException(ErrorCode.ROOM_NOT_RECRUITING);
        }
    }

    private RoomParticipationResponse toResponse(Room room) {
        return new RoomParticipationResponse(
                room.getId(),
                ParticipationStatus.ACTIVE,
                room.getStatus(),
                room.getActiveParticipantCount() + 1,
                room.getCapacity() - room.getActiveParticipantCount());
    }
}
