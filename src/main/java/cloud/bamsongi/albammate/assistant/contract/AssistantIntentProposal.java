package cloud.bamsongi.albammate.assistant.contract;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** provider가 제안한 게임 조회 전 구조화 조건이다. */
public record AssistantIntentProposal(
	String action,
	List<String> categories,
	List<String> mechanisms,
	List<String> themes,
	BigDecimal complexityMax,
	String playTimeMax,
	Integer playerCount) {

	public AssistantIntentProposal {
		action = Objects.requireNonNull(action, "action");
		categories = copyOf(categories, "categories");
		mechanisms = copyOf(mechanisms, "mechanisms");
		themes = copyOf(themes, "themes");
	}

	public AssistantIntentProposal(String action, List<String> categories) {
		this(action, categories, List.of(), List.of(), null, null, null);
	}

	private static List<String> copyOf(List<String> values, String name) {
		return List.copyOf(Objects.requireNonNull(values, name));
	}
}
