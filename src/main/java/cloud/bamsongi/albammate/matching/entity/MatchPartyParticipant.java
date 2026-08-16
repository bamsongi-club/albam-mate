package cloud.bamsongi.albammate.matching.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "match_party_participants")
public class MatchPartyParticipant {

	@EmbeddedId
	private MatchPartyParticipantId id;
	@Column(name = "participant_ref", nullable = false)
	private UUID participantRef;
	@Column(name = "left_at")
	private Instant leftAt;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public static MatchPartyParticipant create(
		long partyId, long userId, UUID participantRef, Instant createdAt) {
		MatchPartyParticipant participant = new MatchPartyParticipant();
		participant.id = new MatchPartyParticipantId(partyId, userId);
		participant.participantRef = participantRef;
		participant.createdAt = createdAt;
		return participant;
	}
}
