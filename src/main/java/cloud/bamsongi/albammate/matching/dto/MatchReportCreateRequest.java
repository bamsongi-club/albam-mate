package cloud.bamsongi.albammate.matching.dto;

import java.util.UUID;

import cloud.bamsongi.albammate.matching.MatchReportReason;
import jakarta.validation.constraints.NotNull;

public record MatchReportCreateRequest(
	@NotNull UUID participantRef,
	@NotNull MatchReportReason reason) {
}
