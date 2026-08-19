package cloud.bamsongi.albammate.matching.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Embeddable
public class MatchPartyParticipantId implements Serializable {

	@Column(name = "party_id")
	private Long partyId;
	@Column(name = "user_id")
	private Long userId;

	public MatchPartyParticipantId(long partyId, long userId) {
		this.partyId = partyId;
		this.userId = userId;
	}
}
