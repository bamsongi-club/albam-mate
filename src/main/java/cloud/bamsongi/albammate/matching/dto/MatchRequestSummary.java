package cloud.bamsongi.albammate.matching.dto;

import java.time.Instant;

import cloud.bamsongi.albammate.matching.entity.MatchRequest;

public record MatchRequestSummary(int minPlayers, int maxPlayers, Instant queuedAt) {

	public static MatchRequestSummary from(MatchRequest request) {
		return new MatchRequestSummary(request.getMinPartySize(), request.getMaxPartySize(), request.getQueuedAt());
	}
}
