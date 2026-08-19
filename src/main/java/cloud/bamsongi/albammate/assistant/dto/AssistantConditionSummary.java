package cloud.bamsongi.albammate.assistant.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** provider 원문 없이 서버가 확인한 구조화 추천 조건만 반환한다. */
public record AssistantConditionSummary(
	List<@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]*") String> categories,
	List<@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]*") String> mechanisms,
	List<@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]*") String> themes,
	@DecimalMin("1.00") @DecimalMax("5.00") BigDecimal complexityMax,
	@Pattern(regexp = "UP_TO_10|OVER_10_TO_20|OVER_20_TO_30|OVER_30_TO_60|OVER_60_UNDER_90|AT_LEAST_90") String playTimeMax,
	@Positive Long gameId,
	@Min(2) @Max(11) Integer playerCount,
	Instant startsAt,
	@Size(max = 50) String region,
	@Pattern(regexp = "ALL_LEVELS|BEGINNER_WELCOME|EXPERIENCED_PREFERRED") String experienceLevel) {

	public AssistantConditionSummary {
		categories = copyOrEmpty(categories);
		mechanisms = copyOrEmpty(mechanisms);
		themes = copyOrEmpty(themes);
	}

	public AssistantConditionSummary(List<String> categories) {
		this(categories, List.of(), List.of(), null, null, null, null, null, null, null);
	}

	public static AssistantConditionSummary empty() {
		return new AssistantConditionSummary(List.of());
	}

	/** 현재 문장에서 확인된 값만 덮어쓰고, 생략된 누적 조건은 보존한다. */
	public AssistantConditionSummary merge(AssistantConditionSummary current) {
		return new AssistantConditionSummary(
			prefer(current.categories, categories),
			prefer(current.mechanisms, mechanisms),
			prefer(current.themes, themes),
			prefer(current.complexityMax, complexityMax),
			prefer(current.playTimeMax, playTimeMax),
			prefer(current.gameId, gameId),
			prefer(current.playerCount, playerCount),
			prefer(current.startsAt, startsAt),
			prefer(current.region, region),
			prefer(current.experienceLevel, experienceLevel));
	}

	public boolean hasRecommendationSearchCondition() {
		return !categories.isEmpty() || !mechanisms.isEmpty() || !themes.isEmpty();
	}

	private static <T> List<T> copyOrEmpty(List<T> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

	private static <T> T prefer(T current, T previous) {
		return current == null ? previous : current;
	}

	private static <T> List<T> prefer(List<T> current, List<T> previous) {
		return current.isEmpty() ? previous : current;
	}
}
