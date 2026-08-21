package cloud.bamsongi.albammate.game.contract;

import java.util.Objects;

/** AI 추천 카드에만 쓰는 공개 게임 projection이다. */
public record AssistantRecommendationCandidate(
	Long id,
	String name,
	String imageUrl,
	String description) {

	public AssistantRecommendationCandidate {
		id = Objects.requireNonNull(id, "id");
		name = Objects.requireNonNull(name, "name");
		description = Objects.requireNonNull(description, "description");
	}
}
