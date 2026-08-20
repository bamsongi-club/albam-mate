package cloud.bamsongi.albammate.matching.dto;

import java.time.Instant;
import java.util.List;

public record MatchChatHandoff(
	long partyId,
	List<MatchPartyMember> members,
	Instant chatOpenedAt,
	Instant closesAt,
	String historyPath,
	String sendPath,
	String webSocketPath) {
}
