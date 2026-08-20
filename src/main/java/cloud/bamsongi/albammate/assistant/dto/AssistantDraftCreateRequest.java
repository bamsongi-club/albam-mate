package cloud.bamsongi.albammate.assistant.dto;

import java.time.Instant;

public record AssistantDraftCreateRequest(String roomType, String title, String description, Long gameId,
	String experienceLevel, Boolean isRulemasterLed, Instant startsAt, String region, String place,
	Integer recruitmentCapacity) {
}
