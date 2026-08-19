package cloud.bamsongi.albammate.game.contract;

import java.math.BigDecimal;
import java.util.List;

/** AI-02가 서버 검증한 카테고리 조건으로 후보를 읽는 game 경계다. */
public interface AssistantGameCandidateQuery {

	List<GameSummary> findCandidates(Criteria criteria);

	record Criteria(
		List<String> categories,
		List<String> mechanisms,
		List<String> themes,
		BigDecimal complexityMax,
		String playTimeMax,
		Long gameId,
		Integer playerCount) {

		public Criteria {
			categories = copyOrEmpty(categories);
			mechanisms = copyOrEmpty(mechanisms);
			themes = copyOrEmpty(themes);
		}

		public Criteria(List<String> categories) {
			this(categories, List.of(), List.of(), null, null, null, null);
		}

		private static List<String> copyOrEmpty(List<String> values) {
			return values == null ? List.of() : List.copyOf(values);
		}
	}
}
