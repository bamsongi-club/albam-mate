package cloud.bamsongi.albammate.assistant.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

/** ACTIVE 초안의 명시적 확인 전 입력 보완 요청이다. */
public record AssistantDraftPatchRequest(
	@NotNull Long draftVersion,
	String roomType,
	String title,
	String description,
	Long gameId,
	String experienceLevel,
	Boolean isRulemasterLed,
	Instant startsAt,
	String region,
	String place,
	Integer recruitmentCapacity) {
	public boolean hasInputChange() {
		return roomType != null || title != null || description != null || gameId != null
			|| experienceLevel != null || isRulemasterLed != null || startsAt != null
			|| region != null || place != null || recruitmentCapacity != null;
	}
}
