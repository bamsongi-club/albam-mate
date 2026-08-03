package cloud.bamsongi.albammate.game.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 빈 query parameter가 {@code null}로 바인딩돼도 nullable wrapper setter가
 * {@code page=0}, {@code size=10}, {@code upcomingOnly=false} 기본값을 보존한다.
 */
public class GameListRequest {

	private String keyword;
	private boolean upcomingOnly;

	@Min(1) @Max(10) private Integer playerCount;

	private GamePlayTimeFilter playTime;

	@DecimalMin("1.00") @DecimalMax("5.00") private BigDecimal complexityMin;

	@DecimalMin("1.00") @DecimalMax("5.00") private BigDecimal complexityMax;

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

	public GamePlayTimeFilter getPlayTime() {
		return playTime;
	}

	public void setPlayTime(GamePlayTimeFilter playTime) {
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

	@AssertTrue(message = "complexityMin은 complexityMax보다 클 수 없습니다.") public boolean isComplexityRangeValid() {
		return complexityMin == null || complexityMax == null || complexityMin.compareTo(complexityMax) <= 0;
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
