package cloud.bamsongi.albammate.matching.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.matching.MatchPartyStatus;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipantId;

public interface MatchPartyParticipantRepository extends JpaRepository<MatchPartyParticipant, MatchPartyParticipantId> {

	@Query("""
		select participant.participantRef
		from MatchPartyParticipant participant
		where participant.id.partyId = :partyId
		order by participant.createdAt asc
		""")
	List<UUID> findParticipantRefsByPartyId(@Param("partyId")
	Long partyId);

	@Query("""
		select participant
		from MatchPartyParticipant participant
		where participant.id.partyId = :partyId
		  and participant.id.userId = :userId
		  and participant.leftAt is null
		""")
	Optional<MatchPartyParticipant> findCurrentByPartyIdAndUserId(
		@Param("partyId")
		Long partyId, @Param("userId")
		Long userId);

	@Query("""
		select case when count(participant) > 0 then true else false end
		from MatchParty party, MatchPartyParticipant participant
		where party.id = :partyId
		  and party.status = :status
		  and participant.id.partyId = party.id
		  and participant.id.userId = :userId
		  and participant.leftAt is null
		""")
	boolean existsCurrentParticipantForPartyStatus(
		@Param("partyId")
		Long partyId,
		@Param("userId")
		Long userId,
		@Param("status")
		MatchPartyStatus status);
}
