package cloud.bamsongi.albammate.game.dto;

/** 검증된 최대 플레이 시간을 기준으로 하는 게임 목록 시간 조건이다. */
public enum GamePlayTimeFilter {

	SHORT(null, 20),
	MEDIUM(20, 60),
	LONG(60, null);

	private final Integer minExclusive;
	private final Integer maxInclusive;

	GamePlayTimeFilter(Integer minExclusive, Integer maxInclusive) {
		this.minExclusive = minExclusive;
		this.maxInclusive = maxInclusive;
	}

	public Integer getMinExclusive() {
		return minExclusive;
	}

	public Integer getMaxInclusive() {
		return maxInclusive;
	}
}
