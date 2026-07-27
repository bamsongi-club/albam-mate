package cloud.bamsongi.albammate.room;

import cloud.bamsongi.albammate.game.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.room.entity.RoomStatus;
import cloud.bamsongi.albammate.room.entity.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RoomUpcomingRoomCountQuery implements UpcomingRoomCountQuery {

    private static final List<RoomStatus> EXCLUDED_STATUSES =
            List.of(RoomStatus.CANCELED, RoomStatus.FINISHED);

    private final RoomRepository roomRepository;

    public RoomUpcomingRoomCountQuery(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    @Override
    public Map<Long, Long> findUpcomingRoomCounts(Collection<Long> gameIds, Instant now) {
        if (gameIds.isEmpty()) {
            return Map.of();
        }

        return roomRepository
                .findUpcomingRoomCounts(gameIds, RoomType.GAME_FOCUSED, now, EXCLUDED_STATUSES)
                .stream()
                .collect(
                        Collectors.toMap(
                                RoomRepository.UpcomingRoomCount::getGameId,
                                RoomRepository.UpcomingRoomCount::getRoomCount));
    }
}
