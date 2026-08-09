package cloud.bamsongi.albammate.game.dto;

/**
 * {@code min_age}를 기준으로 하는 게임 목록 연령대 구간이다.
 *
 * <p>구간 경계는 정확히 한 구간에만 속한다. {@code min_age}가 없는 게임은 어느 구간에도 걸리지 않는다.
 */
public enum GameAgeBandFilter {

	/** 8세 이하. */
	UP_TO_8(null, 8),
	/** 9세 이상 12세 이하. */
	FROM_9_TO_12(9, 12),
	/** 13세 이상 15세 이하. */
	FROM_13_TO_15(13, 15),
	/** 16세 이상. */
	AT_LEAST_16(16, null);

	private final Integer minInclusive;
	private final Integer maxInclusive;

	GameAgeBandFilter(Integer minInclusive, Integer maxInclusive) {
		this.minInclusive = minInclusive;
		this.maxInclusive = maxInclusive;
	}

	public Integer getMinInclusive() {
		return minInclusive;
	}

	public Integer getMaxInclusive() {
		return maxInclusive;
	}
}
