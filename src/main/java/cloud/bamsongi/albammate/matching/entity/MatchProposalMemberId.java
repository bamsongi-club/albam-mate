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
public class MatchProposalMemberId implements Serializable {

	@Column(name = "proposal_id")
	private Long proposalId;
	@Column(name = "match_request_id")
	private Long matchRequestId;

	public MatchProposalMemberId(long proposalId, long matchRequestId) {
		this.proposalId = proposalId;
		this.matchRequestId = matchRequestId;
	}
}
