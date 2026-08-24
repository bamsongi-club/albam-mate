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
 * SEARCH-04 의미 검색 요청이다.
 *
 * <p>자연어 {@code query}와 기존 P1 hard filter를 함께 받는다. {@code query}를 제외한 필터 필드의 검증 규칙은
 * {@link GameListRequest}와 같다. 이 요청은 {@link #toGameListRequest()}로 기존 {@link GameListRequest}
 * 경로에 필터만 전달하고, {@code keyword}는 채우지 않는다.
 */
public class SemanticGameSearchRequest {

	/** 승인되지 않은 임의 확장을 막기 위해 query 최대 길이를 고정한다. */
	public static final int MAX_QUERY_LENGTH = 200;

	@NotBlank private String query;

	@Min(1) @Max(10) private Integer playerCount;

	@Min(1) private Integer playerCountMin;

	@Min(1) private Integer playerCountMax;

	private boolean playerCountExact;

	private List<@Min(1) @Max(2) Integer> exclusivePlayerCount;

	private List<GamePlayTimeFilter> playTime;

	@Min(1) private Integer youngestPlayerAge;

	private List<PlayedFilter> playedFilter;

	@DecimalMin("1.00") @DecimalMax("5.00") private BigDecimal complexityMin;

	@DecimalMin("1.00") @DecimalMax("5.00") private BigDecimal complexityMax;

	private List<@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]*") String> mechanism;

	private List<@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]*") String> category;

	private List<@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]*") String> theme;

	private List<ThemeMatch> themeMatch;

	private List<MechanismMatch> mechanismMatch;

	private List<@Min(1) Integer> recommendedPlayerCount;

	private List<@Min(1) Integer> bestPlayerCount;

	@Min(0) private int page = 0;

	@Min(1) @Max(100) private int size = 10;

	public String getQuery() {
		return query;
	}

	public void setQuery(String query) {
		this.query = query;
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

	public Integer getYoungestPlayerAge() {
		return youngestPlayerAge;
	}

	public void setYoungestPlayerAge(Integer youngestPlayerAge) {
		this.youngestPlayerAge = youngestPlayerAge;
	}

	public PlayedFilter getPlayedFilter() {
		if (playedFilter == null || playedFilter.size() != 1 || playedFilter.getFirst() == null) {
			return null;
		}
		return playedFilter.getFirst();
	}

	public void setPlayedFilter(List<PlayedFilter> playedFilter) {
		this.playedFilter = playedFilter;
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

	public List<String> getCategory() {
		return category;
	}

	public void setCategory(List<String> category) {
		this.category = category;
	}

	public List<String> getTheme() {
		return theme;
	}

	public void setTheme(List<String> theme) {
		this.theme = theme;
	}

	public ThemeMatch getThemeMatch() {
		return themeMatch == null || themeMatch.isEmpty() ? ThemeMatch.ANY : themeMatch.getFirst();
	}

	public void setThemeMatch(List<ThemeMatch> themeMatch) {
		this.themeMatch = themeMatch;
	}

	public MechanismMatch getMechanismMatch() {
		return mechanismMatch == null || mechanismMatch.isEmpty() ? MechanismMatch.ANY : mechanismMatch.getFirst();
	}

	public void setMechanismMatch(List<MechanismMatch> mechanismMatch) {
		this.mechanismMatch = mechanismMatch;
	}

	public List<Integer> getRecommendedPlayerCount() {
		return recommendedPlayerCount;
	}

	public void setRecommendedPlayerCount(List<Integer> recommendedPlayerCount) {
		this.recommendedPlayerCount = recommendedPlayerCount;
	}

	public List<Integer> getBestPlayerCount() {
		return bestPlayerCount;
	}

	public void setBestPlayerCount(List<Integer> bestPlayerCount) {
		this.bestPlayerCount = bestPlayerCount;
	}

	@AssertTrue(message = "query는 " + MAX_QUERY_LENGTH + "자를 넘을 수 없습니다.") public boolean isQueryLengthValid() {
		return query == null || query.length() <= MAX_QUERY_LENGTH;
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

	@AssertTrue(message = "playedFilter는 한 번만 전달할 수 있습니다.") public boolean isPlayedFilterSingleValue() {
		return playedFilter == null || (playedFilter.size() == 1 && playedFilter.getFirst() != null);
	}

	@AssertTrue(message = "themeMatch는 한 번만 전달할 수 있습니다.") public boolean isThemeMatchSingleValue() {
		return themeMatch == null || (themeMatch.size() == 1 && themeMatch.getFirst() != null);
	}

	@AssertTrue(message = "mechanismMatch는 한 번만 전달할 수 있습니다.") public boolean isMechanismMatchSingleValue() {
		return mechanismMatch == null || (mechanismMatch.size() == 1 && mechanismMatch.getFirst() != null);
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

	/** query를 제외한 P1 hard filter만 기존 {@link GameListRequest} 경로에 전달한다. */
	public GameListRequest toGameListRequest() {
		GameListRequest request = new GameListRequest();
		request.setPlayerCount(playerCount);
		request.setPlayerCountMin(playerCountMin);
		request.setPlayerCountMax(playerCountMax);
		request.setPlayerCountExact(playerCountExact);
		request.setExclusivePlayerCount(exclusivePlayerCount);
		request.setPlayTime(playTime);
		request.setYoungestPlayerAge(youngestPlayerAge);
		request.setPlayedFilter(playedFilter);
		request.setComplexityMin(complexityMin);
		request.setComplexityMax(complexityMax);
		request.setMechanism(mechanism);
		request.setCategory(category);
		request.setTheme(theme);
		request.setThemeMatch(themeMatch);
		request.setMechanismMatch(mechanismMatch);
		request.setRecommendedPlayerCount(recommendedPlayerCount);
		request.setBestPlayerCount(bestPlayerCount);
		return request;
	}
}
