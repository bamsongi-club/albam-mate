package cloud.bamsongi.albammate.room.service;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.RoomStateReconciliationCoordinator;
import cloud.bamsongi.albammate.room.dto.PublicRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomPageResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class RoomListQueryService {

    private final RoomStateReconciliationCoordinator reconciliationCoordinator;
    private final RoomListReadService roomListReadService;
    private final GameQuery gameQuery;
    private final Clock clock;

    public RoomListQueryService(
            RoomStateReconciliationCoordinator reconciliationCoordinator,
            RoomListReadService roomListReadService,
            GameQuery gameQuery,
            Clock clock) {
        this.reconciliationCoordinator = reconciliationCoordinator;
        this.roomListReadService = roomListReadService;
        this.gameQuery = gameQuery;
        this.clock = clock;
    }

    /** 상태 보정이 끝난 시점의 공개 방을 고정 정렬과 요청자 기준 참가 가능 여부로 반환한다. */
    public RoomPageResponse findPage(
            RoomType roomType,
            Long gameId,
            String keyword,
            int page,
            int size,
            Optional<Long> currentUserId) {
        Instant requestTime = Instant.now(clock);
        reconciliationCoordinator.reconcileDueRooms(requestTime);

        PageRequest pageable =
                PageRequest.of(
                        page, size, Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id")));
        String normalizedKeyword = normalizeKeyword(keyword);
        RoomListReadService.RoomListReadResult readResult =
                roomListReadService.findPublicRooms(
                        roomType, gameId, normalizedKeyword, pageable, currentUserId.orElse(null));
        Map<Long, GameSummary> gameSummaries = findGameSummaries(readResult.rooms().getContent());
        Page<PublicRoomResponse> response =
                readResult
                        .rooms()
                        .map(
                                room ->
                                        toResponse(
                                                room,
                                                getGameSummary(room, gameSummaries),
                                                requestTime,
                                                currentUserId,
                                                readResult.activeParticipationRoomIds()));
        return RoomPageResponse.from(response);
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalizedKeyword = keyword.strip();
        return normalizedKeyword.isEmpty()
                ? null
                : normalizedKeyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private Map<Long, GameSummary> findGameSummaries(List<Room> rooms) {
        Set<Long> gameIds =
                rooms.stream()
                        .map(Room::getGameId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        return gameIds.isEmpty() ? Map.of() : gameQuery.findSummariesById(gameIds);
    }

    private GameSummary getGameSummary(Room room, Map<Long, GameSummary> gameSummaries) {
        if (room.getGameId() == null) {
            return null;
        }
        return Optional.ofNullable(gameSummaries.get(room.getGameId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
    }

    private PublicRoomResponse toResponse(
            Room room,
            GameSummary game,
            Instant requestTime,
            Optional<Long> currentUserId,
            java.util.Set<Long> activeParticipationRoomIds) {
        int remainingRecruitmentSeats = room.getCapacity() - room.getActiveParticipantCount();
        boolean joinable =
                currentUserId
                                .filter(userId -> !userId.equals(room.getHostUserId()))
                                .filter(
                                        userId ->
                                                !activeParticipationRoomIds.contains(room.getId()))
                                .isPresent()
                        && room.getStatus() == RoomStatus.RECRUITING
                        && requestTime.isBefore(room.getStartAt())
                        && remainingRecruitmentSeats >= 1;
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
                room.getActiveParticipantCount() + 1,
                remainingRecruitmentSeats,
                room.getStatus(),
                joinable);
    }
}
