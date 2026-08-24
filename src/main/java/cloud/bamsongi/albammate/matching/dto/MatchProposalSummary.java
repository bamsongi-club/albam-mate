package cloud.bamsongi.albammate.matching.dto;

import java.time.Instant;
import java.util.List;

import cloud.bamsongi.albammate.matching.MatchProposalResponseStatus;

public record MatchProposalSummary(
	long proposalId,
	int partySize,
	List<MatchProposalMemberPreview> members,
	Instant respondBy,
	MatchProposalResponseStatus myResponse) {
}
