package cloud.bamsongi.albammate.matching.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MatchRequestCreateRequest(
	@NotNull @Min(1) @Max(Short.MAX_VALUE) Integer minPlayers,
	@NotNull @Min(1) @Max(Short.MAX_VALUE) Integer maxPlayers) {

	public boolean hasValidRange() {
		return minPlayers != null && maxPlayers != null && minPlayers <= maxPlayers;
	}
}
