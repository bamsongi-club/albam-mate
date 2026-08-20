package cloud.bamsongi.albammate.assistant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** AI-02에 전달하는 현재 한 문장의 자연어 추천 요청이다. */
public record AssistantRecommendationRequest(
	@NotBlank @Size(max = 2000) @Pattern(regexp = "^[^\\p{Cntrl}]*$") String message,
	@Valid AssistantConditionSummary conditions) {

	public AssistantRecommendationRequest {
		message = message == null ? null : message.strip();
	}
}
