package cloud.bamsongi.albammate.matching.dto;

import java.time.Instant;

public record MatchReportReceiptResponse(Instant receivedAt, boolean alreadyReceived) {
}
