package cloud.bamsongi.albammate.room;

import cloud.bamsongi.albammate.game.GameQuery;
import cloud.bamsongi.albammate.game.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.entity.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.user.service.UserQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomCreateService {

    private final RoomRepository roomRepository;
    private final GameQuery gameQuery;
    private final UserQuery userQuery;
    private final Clock clock;

    public RoomCreateService(
            RoomRepository roomRepository, GameQuery gameQuery, UserQuery userQuery, Clock clock) {
        this.roomRepository = roomRepository;
        this.gameQuery = gameQuery;
        this.userQuery = userQuery;
        this.clock = clock;
    }

    /** 로그인한 사용자를 주최자로 기록하고 모집 중인 방을 생성한다. */
    @Transactional
    public ParticipantRoomResponse createRoom(long currentUserId, CreateRoomRequest request) {
        String hostNickname =
                userQuery
                        .findNicknameById(currentUserId)
                        .orElseThrow(UnauthenticatedException::new);
        GameSummary game = resolveGame(request);
        validateStartsAt(request.startsAt());

        Room room =
                Room.create(
                        currentUserId,
                        request.roomType(),
                        request.title(),
                        request.description(),
                        request.gameId(),
                        request.experienceLevel(),
                        request.isRulemasterLed(),
                        request.startsAt(),
                        request.place(),
                        request.recruitmentCapacity());
        Room savedRoom = roomRepository.save(room);
        NicknameSummary host = new NicknameSummary(hostNickname);
        return toResponse(savedRoom, game, host);
    }

    private GameSummary resolveGame(CreateRoomRequest request) {
        if (request.roomType() == RoomType.GAME_FOCUSED && request.gameId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (request.gameId() == null) {
            return null;
        }
        Optional<GameSummary> game = gameQuery.findSummaryById(request.gameId());
        return game.orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
    }

    private void validateStartsAt(Instant startsAt) {
        if (!startsAt.isAfter(Instant.now(clock))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private ParticipantRoomResponse toResponse(Room room, GameSummary game, NicknameSummary host) {
        int participantCount = room.getActiveParticipantCount() + 1;
        int remainingRecruitmentSeats = room.getCapacity() - room.getActiveParticipantCount();
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
                participantCount,
                remainingRecruitmentSeats,
                room.getStatus(),
                false,
                MyRole.HOST,
                room.getPlace(),
                host,
                java.util.List.of(host));
    }
}
