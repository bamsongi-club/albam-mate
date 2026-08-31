package cloud.bamsongi.albammate.game.contract;

import java.util.List;

/**
 * provider가 반환한 자연어 레이블을 game 소유 catalog 코드로 해석하는 경계다.
 *
 * provider는 카탈로그 코드 체계를 알 수 없으므로(예: theme 코드는 {@code HORROR_BGG_1024} 형태다)
 * 레이블만 반환하고, 코드 해석은 어휘를 소유한 game 모듈에서 수행한다.
 * 해석하지 못한 레이블은 요청 실패로 다루지 않고 조용히 버린다.
 */
public interface AssistantVocabularyQuery {

	/**
	 * 세 축의 레이블을 한 번에 해석한다.
	 *
	 * provider는 어떤 낱말이 어느 축에 속하는지 알 수 없어(예: "협력"은 category가 아니라 mechanism이다)
	 * 축을 잘못 지정할 수 있다. 따라서 provider가 지정한 축을 먼저 보되, 없으면 다른 축에서도 찾아
	 * 실제로 그 낱말을 가진 축에 배치한다.
	 */
	Resolved resolve(List<String> categoryLabels, List<String> mechanismLabels, List<String> themeLabels);

	/** 카탈로그에 실재하는 코드만 담은 해석 결과다. */
	record Resolved(List<String> categories, List<String> mechanisms, List<String> themes) {

		public Resolved {
			categories = copyOrEmpty(categories);
			mechanisms = copyOrEmpty(mechanisms);
			themes = copyOrEmpty(themes);
		}

		public static Resolved empty() {
			return new Resolved(List.of(), List.of(), List.of());
		}

		private static List<String> copyOrEmpty(List<String> values) {
			return values == null ? List.of() : List.copyOf(values);
		}
	}
}
