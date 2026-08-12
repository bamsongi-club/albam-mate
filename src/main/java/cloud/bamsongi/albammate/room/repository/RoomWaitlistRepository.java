package cloud.bamsongi.albammate.room.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.entity.RoomWaitlistId;

public interface RoomWaitlistRepository extends JpaRepository<RoomWaitlist, RoomWaitlistId> {

	/** 상태와 position을 같은 SQL snapshot에서 읽어 중간 전이를 섞지 않는다. */
	@Query(value = """
		select waitlist.status as "status", waitlist.queue_order as "queueOrder",
		    case when waitlist.status = 'WAITING' then (
		        select count(*) + 1
		        from room_waitlists preceding
		        where preceding.room_id = waitlist.room_id
		          and preceding.status = 'WAITING'
		          and preceding.queue_order < waitlist.queue_order
		    ) else null end as "position"
		from room_waitlists waitlist
		where waitlist.room_id = :roomId and waitlist.user_id = :userId
		""", nativeQuery = true)
	Optional<RoomWaitlistStateProjection> findStateWithPositionByRoomIdAndUserId(
		@Param("roomId")
		Long roomId, @Param("userId")
		Long userId);

	@Query(value = """
		select user_id as "userId", queue_order as "queueOrder"
		from room_waitlists
		where room_id = :roomId and status = 'WAITING'
		order by queue_order asc
		limit 1
		""", nativeQuery = true)
	Optional<RoomWaitlistCandidateProjection> findFirstWaitingByRoomId(@Param("roomId")
	Long roomId);

	@Query(value = """
		select room_id
		from room_waitlists
		where user_id = :userId and room_id in :roomIds and status = 'WAITING'
		order by room_id asc
		""", nativeQuery = true)
	List<Long> findWaitingRoomIdsByUserIdAndRoomIds(
		@Param("userId")
		Long userId, @Param("roomIds")
		List<Long> roomIds);

	@Query(value = "select nextval('room_waitlist_queue_order_seq')", nativeQuery = true)
	long getNextQueueOrder();

	@Modifying(flushAutomatically = true)
	@Query(value = """
		update room_waitlists
		set status = 'CANCELED', updated_at = :requestTime
		where room_id = :roomId and user_id = :userId
		  and status = 'WAITING' and queue_order = :expectedQueueOrder
		""", nativeQuery = true)
	int cancelWaiting(
		@Param("roomId")
		Long roomId,
		@Param("userId")
		Long userId,
		@Param("expectedQueueOrder")
		long expectedQueueOrder,
		@Param("requestTime")
		Instant requestTime);

	@Modifying(flushAutomatically = true)
	@Query(value = """
		update room_waitlists
		set status = 'PROMOTED', updated_at = :requestTime
		where room_id = :roomId and user_id = :userId
		  and status = 'WAITING' and queue_order = :expectedQueueOrder
		""", nativeQuery = true)
	int promoteWaiting(
		@Param("roomId")
		Long roomId,
		@Param("userId")
		Long userId,
		@Param("expectedQueueOrder")
		long expectedQueueOrder,
		@Param("requestTime")
		Instant requestTime);

	@Modifying(flushAutomatically = true)
	@Query(value = """
		update room_waitlists
		set status = 'EXPIRED', updated_at = :requestTime
		where room_id = :roomId and status = 'WAITING'
		""", nativeQuery = true)
	int expireAllWaiting(@Param("roomId")
	Long roomId, @Param("requestTime")
	Instant requestTime);

	@Modifying(flushAutomatically = true)
	@Query(value = """
		update room_waitlists
		set status = 'ROOM_CANCELED', updated_at = :requestTime
		where room_id = :roomId and status = 'WAITING'
		""", nativeQuery = true)
	int cancelAllWaiting(@Param("roomId")
	Long roomId, @Param("requestTime")
	Instant requestTime);

	@Modifying(flushAutomatically = true)
	@Query(value = """
		update room_waitlists
		set status = 'WAITING', queue_order = :queueOrder, queued_at = :requestTime, updated_at = :requestTime
		where room_id = :roomId and user_id = :userId and status in ('CANCELED', 'PROMOTED')
		""", nativeQuery = true)
	int reactivateWaiting(
		@Param("roomId")
		Long roomId,
		@Param("userId")
		Long userId,
		@Param("queueOrder")
		long queueOrder,
		@Param("requestTime")
		Instant requestTime);
}
