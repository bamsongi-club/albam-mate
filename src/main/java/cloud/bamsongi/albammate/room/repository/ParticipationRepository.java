package cloud.bamsongi.albammate.room.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;

public interface ParticipationRepository extends JpaRepository<Participation, Long> {

	Optional<Participation> findByRoomIdAndUserId(Long roomId, Long userId);

	List<Participation> findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
		Long roomId, ParticipationStatus status);

	/** 방 취소가 같은 트랜잭션에서 고정할 ACTIVE 수신자 스냅샷을 조회한다. */
	@Query("""
		select participation.userId
		from Participation participation
		where participation.room.id = :roomId
		  and participation.status = :status
		order by participation.joinedAt asc, participation.id asc
		""")
	List<Long> findUserIdsByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
		@Param("roomId")
		Long roomId, @Param("status")
		ParticipationStatus status);
}
