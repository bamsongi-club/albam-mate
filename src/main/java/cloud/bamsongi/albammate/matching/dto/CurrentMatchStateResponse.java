package cloud.bamsongi.albammate.matching.dto;

import java.time.Instant;
import java.util.List;

import cloud.bamsongi.albammate.matching.MatchCurrentState;
import cloud.bamsongi.albammate.matching.entity.MatchParty;
import cloud.bamsongi.albammate.matching.entity.MatchRequest;

public record CurrentMatchStateResponse(
	Instant operationTime,
	MatchCurrentState state,
	MatchRequestSummary request,
	MatchProposalSummary proposal,
	MatchPreparingSummary preparing,
	MatchChatHandoff chat) {

	public static CurrentMatchStateResponse empty(Instant operationTime) {
		return new CurrentMatchStateResponse(operationTime, null, null, null, null, null);
	}

	public static CurrentMatchStateResponse waiting(Instant operationTime, MatchRequest request) {
		return new CurrentMatchStateResponse(operationTime, MatchCurrentState.WAITING,
			MatchRequestSummary.from(request), null, null, null);
	}

	public static CurrentMatchStateResponse paused(Instant operationTime, MatchRequest request) {
		return new CurrentMatchStateResponse(operationTime, MatchCurrentState.PAUSED, MatchRequestSummary.from(request),
			null, null, null);
	}

	public static CurrentMatchStateResponse proposed(
		Instant operationTime, MatchRequest request, MatchProposalSummary proposal) {
		return new CurrentMatchStateResponse(operationTime, MatchCurrentState.PROPOSED,
			MatchRequestSummary.from(request), proposal, null, null);
	}

	public static CurrentMatchStateResponse preparing(
		Instant operationTime, MatchParty party, List<MatchPreparingMember> members) {
		return new CurrentMatchStateResponse(operationTime, MatchCurrentState.PREPARING, null, null,
			new MatchPreparingSummary(
				party.getPreparingStartedAt(), party.getPreparingStartedAt().plusSeconds(300), members),
			null);
	}

	public static CurrentMatchStateResponse active(Instant operationTime, MatchParty party,
		List<MatchPartyMember> members) {
		long partyId = party.getId();
		return new CurrentMatchStateResponse(operationTime, MatchCurrentState.ACTIVE, null, null, null,
			new MatchChatHandoff(
				partyId,
				members,
				party.getChatOpenedAt(),
				party.getClosesAt(),
				"/api/matches/parties/" + partyId + "/chat/messages",
				"/api/matches/parties/" + partyId + "/chat/messages",
				"/api/matches/parties/" + partyId + "/chat/ws"));
	}
}
