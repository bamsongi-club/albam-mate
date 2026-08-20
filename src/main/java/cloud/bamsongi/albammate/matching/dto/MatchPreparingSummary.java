package cloud.bamsongi.albammate.matching.dto;

import java.time.Instant;

public record MatchPreparingSummary(Instant preparingStartedAt, Instant prepareUntil) {
}
