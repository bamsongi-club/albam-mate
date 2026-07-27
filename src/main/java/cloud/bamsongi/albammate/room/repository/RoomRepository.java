package cloud.bamsongi.albammate.room.repository;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<Room, Long> {

    @Query(
            """
            select r.gameId as gameId, count(r.id) as roomCount
            from Room r
            where r.gameId in :gameIds
              and r.roomType = :roomType
              and r.startAt > :now
              and r.status not in :excludedStatuses
            group by r.gameId
            """)
    List<UpcomingRoomCount> findUpcomingRoomCounts(
            @Param("gameIds") Collection<Long> gameIds,
            @Param("roomType") RoomType roomType,
            @Param("now") Instant now,
            @Param("excludedStatuses") Collection<RoomStatus> excludedStatuses);

    interface UpcomingRoomCount {

        Long getGameId();

        Long getRoomCount();
    }
}
