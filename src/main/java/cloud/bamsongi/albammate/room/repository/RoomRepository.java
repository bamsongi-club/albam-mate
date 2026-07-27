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

    /** 상태와 시간 경계를 만족하는 방만 읽어 일괄 보정 대상 범위를 제한한다. */
    @Query(
            """
            select room
            from Room room
            where (room.status = cloud.bamsongi.albammate.room.enums.RoomStatus.RECRUITING
                    and room.startAt <= :requestTime)
                or (room.status = cloud.bamsongi.albammate.room.enums.RoomStatus.CLOSED
                    and room.startAt <= :finishedThreshold)
            """)
    List<Room> findDueRooms(
            @Param("requestTime") Instant requestTime,
            @Param("finishedThreshold") Instant finishedThreshold);

    interface UpcomingRoomCount {

        Long getGameId();

        Long getRoomCount();
    }
}
