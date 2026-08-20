package cloud.bamsongi.albammate.game.contract;

import java.util.Objects;

import cloud.bamsongi.albammate.game.service.GameListSearchCriteria;

/** 인증·입력 검증을 마친 자연어 query와 P1 필터를 전달하는 내부 입력값이다. */
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
