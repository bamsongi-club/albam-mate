package cloud.bamsongi.albammate.matching.dto;

import java.time.Instant;
import java.util.List;

public record MatchPreparingSummary(
	Instant preparingStartedAt, Instant prepareUntil, List<MatchPreparingMember> members) {
}
