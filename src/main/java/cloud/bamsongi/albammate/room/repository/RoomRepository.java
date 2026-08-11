package cloud.bamsongi.albammate.room.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import jakarta.persistence.LockModeType;

public interface RoomRepository extends JpaRepository<Room, Long> {

	/** 활성 대기 등록 전에 읽은 ROOM version이 아직 최신일 때만 claim한다. */
	@Modifying(flushAutomatically = true)
	@Query("""
		update Room room
		set room.version = room.version + 1
		where room.id = :roomId and room.version = :expectedVersion
		""")
	int claimVersion(@Param("roomId")
	Long roomId, @Param("expectedVersion")
	Long expectedVersion);

	/** 채팅 접근과 후속 동작을 한 트랜잭션으로 묶기 위해 ROOM 행의 공유 잠금을 얻는다. */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select room from Room room where room.id = :roomId")
	Optional<Room> findByIdForChatAccess(
		@Param("roomId")
		Long roomId);

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
		        and room.startAt <= :requestTime
		        and exists (
		            select waitlist.id
		            from RoomWaitlist waitlist
		            where waitlist.id.roomId = room.id
		              and waitlist.status = cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus.WAITING
		        ))
		    or (room.status = cloud.bamsongi.albammate.room.enums.RoomStatus.CLOSED
		        and room.startAt <= :finishedThreshold)
		""")
	List<Room> findDueRooms(
		@Param("requestTime")
		Instant requestTime,
		@Param("finishedThreshold")
		Instant finishedThreshold);

	/** 시작 경계의 상태 보정이 필요한 모집 중 ROOM ID를 논리적 due 순서로 제한 선별한다. */
	@Query("""
		select room.id as roomId, room.startAt as startAt
		from Room room
		where room.status = cloud.bamsongi.albammate.room.enums.RoomStatus.RECRUITING
		  and room.startAt <= :turnCutoff
		  and (:hasCursor = false
		       or room.startAt > :cursorDueAt
		       or (room.startAt = :cursorDueAt and room.id > :cursorRoomId))
		order by room.startAt asc, room.id asc
		""")
	List<DueRoomCandidate> findRecruitingDueRoomCandidates(
		@Param("turnCutoff")
		Instant turnCutoff,
		@Param("cursorDueAt")
		Instant cursorDueAt,
		@Param("cursorRoomId")
		Long cursorRoomId,
		@Param("hasCursor")
		boolean hasCursor,
		Pageable pageable);

	/** 시작 경계에 남은 WAITING이 있는 CLOSED ROOM ID를 제한 선별한다. */
	@Query("""
		select room.id as roomId, room.startAt as startAt
		from Room room
		where room.status = cloud.bamsongi.albammate.room.enums.RoomStatus.CLOSED
		  and room.startAt <= :turnCutoff
		  and exists (
		      select waitlist.id
		      from RoomWaitlist waitlist
		      where waitlist.id.roomId = room.id
		        and waitlist.status = cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus.WAITING
		  )
		  and (:hasCursor = false
		       or room.startAt > :cursorDueAt
		       or (room.startAt = :cursorDueAt and room.id > :cursorRoomId))
		order by room.startAt asc, room.id asc
		""")
	List<DueRoomCandidate> findClosedWaitingDueRoomCandidates(
		@Param("turnCutoff")
		Instant turnCutoff,
		@Param("cursorDueAt")
		Instant cursorDueAt,
		@Param("cursorRoomId")
		Long cursorRoomId,
		@Param("hasCursor")
		boolean hasCursor,
		Pageable pageable);

	/** 시작 경계 대기열이 끝난 CLOSED ROOM ID를 종료 시각 순서로 제한 선별한다. */
	@Query("""
		select room.id as roomId, room.startAt as startAt
		from Room room
		where room.status = cloud.bamsongi.albammate.room.enums.RoomStatus.CLOSED
		  and room.startAt <= :finishBoundaryStartAt
		  and not exists (
		      select waitlist.id
		      from RoomWaitlist waitlist
		      where waitlist.id.roomId = room.id
		        and waitlist.status = cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus.WAITING
		  )
		  and (:hasCursor = false
		       or room.startAt > :cursorFinishStartAt
		       or (room.startAt = :cursorFinishStartAt and room.id > :cursorRoomId))
		order by room.startAt asc, room.id asc
		""")
	List<DueRoomCandidate> findClosedFinishDueRoomCandidates(
		@Param("finishBoundaryStartAt")
		Instant finishBoundaryStartAt,
		@Param("cursorFinishStartAt")
		Instant cursorFinishStartAt,
		@Param("cursorRoomId")
		Long cursorRoomId,
		@Param("hasCursor")
		boolean hasCursor,
		Pageable pageable);

	@Query("""
		select room
		from Room room
		where (:roomType is null or room.roomType = :roomType)
		  and room.status in :storedPublicStatuses
		  and room.startAt > :effectiveFinishedAt
		  and (
		      :statusFilterEnabled = false
		      or (:recruitingStatusFilter = true
		          and room.status = cloud.bamsongi.albammate.room.enums.RoomStatus.RECRUITING
		          and room.startAt > :requestTime)
		      or (:closedStatusFilter = true
		          and (room.status = cloud.bamsongi.albammate.room.enums.RoomStatus.CLOSED
		               or (room.status = cloud.bamsongi.albammate.room.enums.RoomStatus.RECRUITING
		                   and room.startAt <= :requestTime)))
		  )
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
	Page<Room> findPublicRoomsAt(
		@Param("roomType")
		RoomType roomType,
		@Param("statusFilterEnabled")
		boolean statusFilterEnabled,
		@Param("recruitingStatusFilter")
		boolean recruitingStatusFilter,
		@Param("closedStatusFilter")
		boolean closedStatusFilter,
		@Param("requestTime")
		Instant requestTime,
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
		@Param("storedPublicStatuses")
		Collection<RoomStatus> storedPublicStatuses,
		@Param("effectiveFinishedAt")
		Instant effectiveFinishedAt,
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
	Page<Room> findMyRoomsAt(
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

	interface DueRoomCandidate {

		Long getRoomId();

		Instant getStartAt();
	}
}
