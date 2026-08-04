package cloud.bamsongi.albammate.game.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 빈 query parameter가 {@code null}로 바인딩돼도 nullable wrapper setter가
 * {@code page=0}, {@code size=10}, {@code upcomingOnly=false} 기본값을 보존한다.
 *
 * <p>인원 조건은 범위 계열({@code playerCountMin}, {@code playerCountMax}, {@code playerCountExact})과
 * 전용 인원({@code exclusivePlayerCount})으로 나뉘고 두 계열을 함께 전달할 수 없다.
 * {@code playerCountExact}는 범위 경계에 붙는 수정자이므로 최소·최대가 없으면 인원 조건이 없다.
 */
public class GameListRequest {

	private String keyword;
	private boolean upcomingOnly;

	@Min(1) @Max(10) private Integer playerCount;

	@Min(1) private Integer playerCountMin;

	@Min(1) private Integer playerCountMax;

	private boolean playerCountExact;

	private List<@Min(1) @Max(2) Integer> exclusivePlayerCount;

	private List<GamePlayTimeFilter> playTime;

	@DecimalMin("1.00") @DecimalMax("5.00") private BigDecimal complexityMin;

	@DecimalMin("1.00") @DecimalMax("5.00") private BigDecimal complexityMax;

	private List<@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]*") String> mechanism;

	@Min(0) private int page = 0;

	@Min(1) @Max(100) private int size = 10;

	public String getKeyword() {
		return keyword;
	}

	public void setKeyword(String keyword) {
		this.keyword = keyword;
	}

	public boolean isUpcomingOnly() {
		return upcomingOnly;
	}

	public void setUpcomingOnly(Boolean upcomingOnly) {
		this.upcomingOnly = Boolean.TRUE.equals(upcomingOnly);
	}

	public Integer getPlayerCount() {
		return playerCount;
	}

	public void setPlayerCount(Integer playerCount) {
		this.playerCount = playerCount;
	}

	public Integer getPlayerCountMin() {
		return playerCountMin;
	}

	public void setPlayerCountMin(Integer playerCountMin) {
		this.playerCountMin = playerCountMin;
	}

	public Integer getPlayerCountMax() {
		return playerCountMax;
	}

	public void setPlayerCountMax(Integer playerCountMax) {
		this.playerCountMax = playerCountMax;
	}

	public boolean isPlayerCountExact() {
		return playerCountExact;
	}

	public void setPlayerCountExact(Boolean playerCountExact) {
		this.playerCountExact = Boolean.TRUE.equals(playerCountExact);
	}

	public List<Integer> getExclusivePlayerCount() {
		return exclusivePlayerCount;
	}

	public void setExclusivePlayerCount(List<Integer> exclusivePlayerCount) {
		this.exclusivePlayerCount = exclusivePlayerCount;
	}

	public List<GamePlayTimeFilter> getPlayTime() {
		return playTime;
	}

	public void setPlayTime(List<GamePlayTimeFilter> playTime) {
		this.playTime = playTime;
	}

	public BigDecimal getComplexityMin() {
		return complexityMin;
	}

	public void setComplexityMin(BigDecimal complexityMin) {
		this.complexityMin = complexityMin;
	}

	public BigDecimal getComplexityMax() {
		return complexityMax;
	}

	public void setComplexityMax(BigDecimal complexityMax) {
		this.complexityMax = complexityMax;
	}

	public List<String> getMechanism() {
		return mechanism;
	}

	public void setMechanism(List<String> mechanism) {
		this.mechanism = mechanism;
	}

	@AssertTrue(message = "complexityMin은 complexityMax보다 클 수 없습니다.") public boolean isComplexityRangeValid() {
		return complexityMin == null || complexityMax == null || complexityMin.compareTo(complexityMax) <= 0;
	}

	@AssertTrue(message = "playerCountMin은 playerCountMax보다 클 수 없습니다.") public boolean isPlayerCountRangeValid() {
		return playerCountMin == null || playerCountMax == null || playerCountMin <= playerCountMax;
	}

	@AssertTrue(message = "인원 범위 조건과 전용 인원 조건은 함께 전달할 수 없습니다.") public boolean isPlayerCountConditionExclusive() {
		boolean hasRange = playerCountMin != null || playerCountMax != null;
		boolean hasExclusive = exclusivePlayerCount != null
			&& exclusivePlayerCount.stream().anyMatch(Objects::nonNull);
		return !hasRange || !hasExclusive;
	}

	public int getPage() {
		return page;
	}

	public void setPage(Integer page) {
		if (page != null) {
			this.page = page;
		}
	}

	public int getSize() {
		return size;
	}

	public void setSize(Integer size) {
		if (size != null) {
			this.size = size;
		}
	}
}
