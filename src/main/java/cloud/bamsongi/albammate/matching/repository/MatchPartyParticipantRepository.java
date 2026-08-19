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
		  and participant.participantRef = :participantRef
		""")
	Optional<MatchPartyParticipant> findByPartyIdAndParticipantRef(
		@Param("partyId")
		Long partyId,
		@Param("participantRef")
		UUID participantRef);

	@Query("""
		select participant
		from MatchPartyParticipant participant
		where participant.id.partyId = :partyId
		  and participant.id.userId = :userId
		""")
	Optional<MatchPartyParticipant> findParticipantByPartyIdAndUserId(
		@Param("partyId")
		Long partyId,
		@Param("userId")
		Long userId);

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
		select party.status
		from MatchParty party, MatchPartyParticipant participant
		where party.id = :partyId
		  and participant.id.partyId = party.id
		  and participant.id.userId = :userId
		  and participant.leftAt is null
		""")
	Optional<MatchPartyStatus> findCurrentParticipantPartyStatus(
		@Param("partyId")
		Long partyId,
		@Param("userId")
		Long userId);
}
