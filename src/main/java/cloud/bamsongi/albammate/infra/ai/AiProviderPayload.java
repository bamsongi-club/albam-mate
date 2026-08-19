package cloud.bamsongi.albammate.infra.ai;

import java.util.List;
import java.util.Objects;

/** 외부 provider로 전달 가능한 값만 가진 allowlist payload다. */
record AiProviderPayload(
	String instructionVersion,
	String toolName,
	String schemaVersion,
	String referenceZoneId,
	String currentUserSentence,
	List<String> missingFields) {

	AiProviderPayload {
		instructionVersion = Objects.requireNonNull(instructionVersion, "instructionVersion");
		toolName = Objects.requireNonNull(toolName, "toolName");
		schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
		referenceZoneId = Objects.requireNonNull(referenceZoneId, "referenceZoneId");
		currentUserSentence = Objects.requireNonNull(currentUserSentence, "currentUserSentence");
		missingFields = List.copyOf(Objects.requireNonNull(missingFields, "missingFields"));
	}
}
