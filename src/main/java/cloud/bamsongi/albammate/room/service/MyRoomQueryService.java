package cloud.bamsongi.albammate.room.service;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.RoomStateReconciliationCoordinator;
import cloud.bamsongi.albammate.room.dto.MyRoomListItem;
import cloud.bamsongi.albammate.room.dto.MyRoomPageResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.MyRoomRole;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** 상태 보정 후 현재 사용자의 내 모임 목록을 계약 필드로 조립한다. */
@Service
public class MyRoomQueryService {

    private final RoomStateReconciliationCoordinator reconciliationCoordinator;
    private final MyRoomReadService myRoomReadService;
    private final GameQuery gameQuery;
    private final Clock clock;

    public MyRoomQueryService(
            RoomStateReconciliationCoordinator reconciliationCoordinator,
            MyRoomReadService myRoomReadService,
            GameQuery gameQuery,
            Clock clock) {
        this.reconciliationCoordinator =
                Objects.requireNonNull(reconciliationCoordinator, "reconciliationCoordinator");
        this.myRoomReadService = Objects.requireNonNull(myRoomReadService, "myRoomReadService");
        this.gameQuery = Objects.requireNonNull(gameQuery, "gameQuery");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 보정 커밋 뒤 역할 필터·중복 제거·고정 정렬이 적용된 내 모임 페이지를 반환한다. */
    public MyRoomPageResponse findPage(Long currentUserId, MyRoomRole role, int page, int size) {
        Instant requestTime = Instant.now(clock);
        reconciliationCoordinator.reconcileDueRooms(requestTime);

        PageRequest pageable =
                PageRequest.of(
                        page, size, Sort.by(Sort.Order.desc("startAt"), Sort.Order.desc("id")));
        Page<Room> rooms = myRoomReadService.findMyRooms(currentUserId, role, pageable);
        Map<Long, GameSummary> gameSummaries = findGameSummaries(rooms);
        return MyRoomPageResponse.from(
                rooms.map(room -> toResponse(room, currentUserId, gameSummaries)));
    }

    private Map<Long, GameSummary> findGameSummaries(Page<Room> rooms) {
        Set<Long> gameIds =
                rooms.stream()
                        .map(Room::getGameId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        return gameIds.isEmpty() ? Map.of() : gameQuery.findSummariesById(gameIds);
    }

    private MyRoomListItem toResponse(
            Room room, Long currentUserId, Map<Long, GameSummary> gameSummaries) {
        GameSummary game = getGameSummary(room, gameSummaries);
        int remainingRecruitmentSeats = room.getCapacity() - room.getActiveParticipantCount();
        MyRole myRole = room.getHostUserId().equals(currentUserId) ? MyRole.HOST : MyRole.JOINED;
        return new MyRoomListItem(
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
                false,
                myRole,
                myRole == MyRole.JOINED ? ParticipationStatus.ACTIVE : null);
    }

    private GameSummary getGameSummary(Room room, Map<Long, GameSummary> gameSummaries) {
        if (room.getGameId() == null) {
            return null;
        }
        return Optional.ofNullable(gameSummaries.get(room.getGameId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
    }
}
