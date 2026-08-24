package cloud.bamsongi.albammate.game.dto;

/**
 * 검증된 최대 플레이 시간을 기준으로 하는 게임 목록 시간 구간이다.
 *
 * <p>구간 경계는 정확히 한 구간에만 속한다. {@code max_play_time_minutes}는 분 단위 정수이므로
 * 계약의 열린 경계는 같은 결과를 내는 닫힌 정수 구간으로 표현한다. 계약 {@code > 10}은
 * {@code >= 11}, {@code < 90}은 {@code <= 89}다.
 */
public enum GamePlayTimeFilter {

	/** 10분 이내. */
	UP_TO_10(null, 10),
	/** 10분 초과 20분 이하. */
	OVER_10_TO_20(11, 20),
	/** 20분 초과 30분 이하. */
	OVER_20_TO_30(21, 30),
	/** 30분 초과 60분 이하. */
	OVER_30_TO_60(31, 60),
	/** 60분 초과 90분 미만. */
	OVER_60_UNDER_90(61, 89),
	/** 90분 이상. */
	AT_LEAST_90(90, null);

	private final Integer minInclusive;
	private final Integer maxInclusive;

	GamePlayTimeFilter(Integer minInclusive, Integer maxInclusive) {
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
