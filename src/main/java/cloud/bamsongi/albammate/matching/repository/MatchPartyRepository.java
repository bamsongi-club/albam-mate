package cloud.bamsongi.albammate.matching.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.matching.entity.MatchParty;
import jakarta.persistence.LockModeType;

public interface MatchPartyRepository extends JpaRepository<MatchParty, Long> {

	@Query("""
		select party from MatchParty party, MatchPartyParticipant participant
		where participant.id.partyId = party.id
		  and participant.id.userId = :userId
		  and participant.leftAt is null
		  and party.status in ('PREPARING', 'ACTIVE')
		""")
	Optional<MatchParty> findCurrentByUserId(@Param("userId")
	long userId);

	@Query(value = """
		with operation as (
			select clock_timestamp() as operation_time
		)
		select party.id
		from match_parties party
		cross join operation
		where party.id > :afterPartyId
		  and ((party.status = 'PREPARING'
			and party.preparing_started_at <= operation.operation_time)
			or (party.status = 'ACTIVE'
				and (party.closes_at <= operation.operation_time
					or (party.closes_at - interval '1 hour' <= operation.operation_time
						and operation.operation_time < party.closes_at)))
			or (party.status = 'CLOSED'
				and party.purge_after <= operation.operation_time))
		order by party.id asc
		limit :candidateBatchSize
		""", nativeQuery = true)
	List<Long> findLifecycleCandidateIdsAfter(
		@Param("afterPartyId") long afterPartyId,
		@Param("candidateBatchSize") int candidateBatchSize);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select party from MatchParty party where party.id = :partyId")
	Optional<MatchParty> findByIdForUpdate(@Param("partyId")
	Long partyId);
}
