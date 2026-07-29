package cloud.bamsongi.albammate.room.service;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.RoomStateReconciliationCoordinator;
import cloud.bamsongi.albammate.room.dto.NicknameSummary;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.PublicRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomDetailResponse;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoomDetailService {

    private final RoomStateReconciliationCoordinator reconciliationCoordinator;
    private final RoomDetailReadService roomDetailReadService;
    private final GameQuery gameQuery;
    private final UserQuery userQuery;
    private final Clock clock;

    /** 요청 시각으로 상태를 먼저 보정한 뒤, 요청자와 방 관계에 맞는 상세 응답을 조립한다. */
    public RoomDetailResponse findRoomDetail(long roomId, Optional<Long> currentUserId) {
        Instant requestTime = Instant.now(clock);
        reconciliationCoordinator.reconcileRoom(roomId, requestTime);

        RoomDetailReadService.RoomDetailReadResult readResult =
                roomDetailReadService.findRoomDetail(roomId);
        Room room = readResult.room();
        List<Participation> activeParticipations = readResult.activeParticipations();
        boolean isHost = currentUserId.filter(room.getHostUserId()::equals).isPresent();
        boolean isActiveParticipant =
                currentUserId
                        .map(
                                userId ->
                                        activeParticipations.stream()
                                                .anyMatch(
                                                        participation ->
                                                                participation
                                                                        .getUserId()
                                                                        .equals(userId)))
                        .orElse(false);

        if (isFinal(room.getStatus()) && !isHost && !isActiveParticipant) {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }

        GameSummary game = findGameSummary(room);
        int activeParticipantCount = activeParticipations.size();
        int remainingRecruitmentSeats = room.getCapacity() - activeParticipantCount;
        boolean joinable =
                currentUserId.isPresent()
                        && !isHost
                        && !isActiveParticipant
                        && room.getStatus() == RoomStatus.RECRUITING
                        && requestTime.isBefore(room.getStartAt())
                        && remainingRecruitmentSeats >= 1;

        if (!isHost && !isActiveParticipant) {
            return new PublicRoomResponse(
                    room.getId(),
                    room.getRoomType(),
                    room.getTitle(),
                    room.getDescription(),
                    game,
                    room.getExperienceLevel(),
                    room.isRulemasterLed(),
                    room.getStartAt(),
                    room.getRegion(),
                    room.getCapacity(),
                    activeParticipantCount + 1,
                    remainingRecruitmentSeats,
                    room.getStatus(),
                    joinable);
        }

        NicknameSummary host = nicknameSummary(room.getHostUserId());
        List<NicknameSummary> participants = new ArrayList<>();
        participants.add(host);
        activeParticipations.forEach(
                participation -> participants.add(nicknameSummary(participation.getUserId())));
        return new ParticipantRoomResponse(
                room.getId(),
                room.getRoomType(),
                room.getTitle(),
                room.getDescription(),
                game,
                room.getExperienceLevel(),
                room.isRulemasterLed(),
                room.getStartAt(),
                room.getRegion(),
                room.getCapacity(),
                activeParticipantCount + 1,
                remainingRecruitmentSeats,
                room.getStatus(),
                joinable,
                isHost ? MyRole.HOST : MyRole.JOINED,
                room.getPlace(),
                host,
                List.copyOf(participants));
    }

    private GameSummary findGameSummary(Room room) {
        if (room.getGameId() == null) {
            return null;
        }
        return gameQuery
                .findSummaryById(room.getGameId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    private NicknameSummary nicknameSummary(Long userId) {
        return new NicknameSummary(
                userQuery
                        .findNicknameById(userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)));
    }

    private boolean isFinal(RoomStatus status) {
        return status == RoomStatus.CANCELED || status == RoomStatus.FINISHED;
    }
}
