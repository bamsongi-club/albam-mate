package cloud.bamsongi.albammate.matching.entity;

import java.time.Instant;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import cloud.bamsongi.albammate.matching.MatchProposalResponseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "match_proposal_members")
public class MatchProposalMember extends BaseEntity {

	@EmbeddedId
	private MatchProposalMemberId id;
	@Column(name = "user_id", nullable = false)
	private Long userId;
	@Enumerated(EnumType.STRING)
	@Column(name = "response_status", nullable = false, length = 20)
	private MatchProposalResponseStatus responseStatus;
	@Column(name = "responded_at")
	private Instant respondedAt;

	public static MatchProposalMember create(
		long proposalId,
		long matchRequestId,
		long userId,
		MatchProposalResponseStatus responseStatus,
		Instant respondedAt) {
		MatchProposalMember member = new MatchProposalMember();
		member.id = new MatchProposalMemberId(proposalId, matchRequestId);
		member.userId = userId;
		member.responseStatus = responseStatus;
		member.respondedAt = respondedAt;
		return member;
	}
}
