package cloud.bamsongi.albammate.assistant.contract;

import java.util.List;
import java.util.Objects;

/** provider가 제안한 게임 조회 전 구조화 조건이다. */
public record AssistantIntentProposal(String action, List<String> gameStyles) {

	public AssistantIntentProposal {
		action = Objects.requireNonNull(action, "action");
		gameStyles = List.copyOf(Objects.requireNonNull(gameStyles, "gameStyles"));
	}
}
