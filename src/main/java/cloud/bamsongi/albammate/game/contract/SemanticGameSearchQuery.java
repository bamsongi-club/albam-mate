package cloud.bamsongi.albammate.game.contract;

import java.util.Objects;

import cloud.bamsongi.albammate.game.service.GameListSearchCriteria;

/**
 * 인증과 입력 검증을 마친 뒤 의미 검색에 전달하는 요청이다.
 *
 * 자연어 질의로 후보를 찾더라도 인원, 시간 같은 기존 P1 필터는 이 값과 함께 그대로 적용한다.
 */
public record SemanticGameSearchQuery(
	String rawQuery,
	GameListSearchCriteria criteria,
	int page,
	int size) {

	public SemanticGameSearchQuery {
		if (rawQuery == null || rawQuery.isBlank()) {
			throw new IllegalArgumentException("rawQuery must not be blank");
		}
		criteria = Objects.requireNonNull(criteria, "criteria");
		if (page < 0 || size < 1) {
			throw new IllegalArgumentException("page must be non-negative and size must be positive");
		}
	}

	@Override
	public String toString() {
		return "SemanticGameSearchQuery[rawQuery=<redacted>, criteria=" + criteria + ", page=" + page + ", size="
			+ size + "]";
	}
}
