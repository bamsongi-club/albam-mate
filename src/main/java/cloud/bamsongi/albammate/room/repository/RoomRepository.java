package cloud.bamsongi.albammate.room.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;

public interface RoomRepository extends JpaRepository<Room, Long> {

	@Query("""
		select r.gameId as gameId, count(r.id) as roomCount
		from Room r
		where r.roomType = :roomType
		  and r.startAt > :now
		  and r.status not in :excludedStatuses
		group by r.gameId
		""")
	List<UpcomingRoomCount> findAllUpcomingRoomCounts(
		@Param("roomType")
		RoomType roomType,
		@Param("now")
		Instant now,
		@Param("excludedStatuses")
		Collection<RoomStatus> excludedStatuses);

	@Query("""
		select r.gameId as gameId, count(r.id) as roomCount
		from Room r
		where r.gameId in :gameIds
		  and r.roomType = :roomType
		  and r.startAt > :now
		  and r.status not in :excludedStatuses
		group by r.gameId
		""")
	List<UpcomingRoomCount> findUpcomingRoomCounts(
		@Param("gameIds")
		Collection<Long> gameIds,
		@Param("roomType")
		RoomType roomType,
		@Param("now")
		Instant now,
		@Param("excludedStatuses")
		Collection<RoomStatus> excludedStatuses);

	/** 상태와 시간 경계를 만족하는 방만 읽어 일괄 보정 대상 범위를 제한한다. */
	@Query("""
		select room
		from Room room
		where (room.status = cloud.bamsongi.albammate.room.enums.RoomStatus.RECRUITING
		        and room.startAt <= :requestTime)
		    or (room.status = cloud.bamsongi.albammate.room.enums.RoomStatus.CLOSED
		        and room.startAt <= :finishedThreshold)
		""")
	List<Room> findDueRooms(
		@Param("requestTime")
		Instant requestTime,
		@Param("finishedThreshold")
		Instant finishedThreshold);

	@Query("""
		select room
		from Room room
		where (:roomType is null or room.roomType = :roomType)
		  and room.status in :publicStatuses
		  and (:gameId is null or room.gameId = :gameId)
		  and (:keywordFilterEnabled = false
		       or lower(room.title) like concat('%', lower(:keyword), '%') escape '!')
		  and (:startsAtFromFilterEnabled = false or room.startAt >= :startsAtFrom)
		  and (:startsAtToFilterEnabled = false or room.startAt < :startsAtTo)
		  and (:minRemainingSeatsFilterEnabled = false
		       or room.capacity - room.activeParticipantCount >= :minRemainingSeats)
		  and room.experienceLevel in :experienceLevels
		  and (:rulemasterOnly = false or room.rulemasterLed = true)
		""")
	Page<Room> findPublicRooms(
		@Param("roomType")
		RoomType roomType,
		@Param("gameId")
		Long gameId,
		@Param("keywordFilterEnabled")
		boolean keywordFilterEnabled,
		@Param("keyword")
		String keyword,
		@Param("startsAtFromFilterEnabled")
		boolean startsAtFromFilterEnabled,
		@Param("startsAtFrom")
		Instant startsAtFrom,
		@Param("startsAtToFilterEnabled")
		boolean startsAtToFilterEnabled,
		@Param("startsAtTo")
		Instant startsAtTo,
		@Param("minRemainingSeatsFilterEnabled")
		boolean minRemainingSeatsFilterEnabled,
		@Param("minRemainingSeats")
		int minRemainingSeats,
		@Param("experienceLevels")
		Collection<ExperienceLevel> experienceLevels,
		@Param("rulemasterOnly")
		boolean rulemasterOnly,
		@Param("publicStatuses")
		Collection<RoomStatus> publicStatuses,
		Pageable pageable);

	@Query("""
		select room
		from Room room
		where (:roomType is null or room.roomType = :roomType)
		  and room.status in :publicStatuses
		  and (:gameId is null or room.gameId = :gameId)
		""")
	Page<Room> findPublicRoomsWithoutKeyword(
		@Param("roomType")
		RoomType roomType,
		@Param("gameId")
		Long gameId,
		@Param("publicStatuses")
		Collection<RoomStatus> publicStatuses,
		Pageable pageable);

	@Query("""
		select room
		from Room room
		where (:roomType is null or room.roomType = :roomType)
		  and room.status in :publicStatuses
		  and (:gameId is null or room.gameId = :gameId)
		  and lower(room.title) like concat('%', lower(:keyword), '%') escape '!'
		""")
	Page<Room> findPublicRoomsByTitleContainingIgnoreCase(
		@Param("roomType")
		RoomType roomType,
		@Param("gameId")
		Long gameId,
		@Param("keyword")
		String keyword,
		@Param("publicStatuses")
		Collection<RoomStatus> publicStatuses,
		Pageable pageable);

	@Query("""
		select participation.room.id
		from Participation participation
		where participation.userId = :userId
		  and participation.status = cloud.bamsongi.albammate.room.enums.ParticipationStatus.ACTIVE
		  and participation.room.id in :roomIds
		""")
	List<Long> findActiveParticipationRoomIds(
		@Param("userId")
		Long userId, @Param("roomIds")
		Collection<Long> roomIds);

	/** 본인이 주최했거나 현재 활성 참가 중인 방을 역할 범위와 고정 정렬로 조회한다. */
	@Query("""
		select room
		from Room room
		where (:includeHosted = true and room.hostUserId = :userId)
		   or (:includeJoined = true
		       and room.status <> cloud.bamsongi.albammate.room.enums.RoomStatus.CANCELED
		       and exists (
		           select participation.id
		           from Participation participation
		           where participation.room = room
		             and participation.userId = :userId
		             and participation.status = cloud.bamsongi.albammate.room.enums.ParticipationStatus.ACTIVE
		       ))
		""")
	Page<Room> findMyRooms(
		@Param("userId")
		Long userId,
		@Param("includeHosted")
		boolean includeHosted,
		@Param("includeJoined")
		boolean includeJoined,
		Pageable pageable);

	interface UpcomingRoomCount {

		Long getGameId();

		Long getRoomCount();
	}
}
