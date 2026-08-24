package cloud.bamsongi.albammate.game.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.util.StringUtils;

import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.dto.MechanismMatch;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
import cloud.bamsongi.albammate.game.dto.ThemeMatch;
import lombok.Getter;

/** 게임 목록의 선택 조건을 하나의 저장소 조회 경계로 전달하는 불변 값 객체다. */
@Getter
public final class GameListSearchCriteria {

	private static final List<Long> NO_UPCOMING_GAME_IDS = List.of(-1L);

	private final String keyword;
	private final boolean upcomingOnly;
	private final Collection<Long> upcomingGameIds;
	private final Integer playerCount;
	private final Integer playerCountMin;
	private final Integer playerCountMax;
	private final boolean playerCountExact;
	private final List<Integer> exclusivePlayerCounts;
	private final List<GamePlayTimeFilter> playTimes;
	private final Integer youngestPlayerAge;
	private final BigDecimal complexityMin;
	private final BigDecimal complexityMax;
	private final PlayedFilter playedFilter;
	private final Long currentUserId;
	private final List<String> mechanisms;
	private final MechanismMatch mechanismMatch;
	private final List<String> categories;
	private final List<String> themes;
	private final ThemeMatch themeMatch;
	private final List<Integer> recommendedPlayerCounts;
	private final List<Integer> bestPlayerCounts;

	private GameListSearchCriteria(GameListRequest request) {
		String requestKeyword = request.getKeyword();
		this.keyword = StringUtils.hasText(requestKeyword) ? requestKeyword.strip() : null;
		this.upcomingOnly = request.isUpcomingOnly();
		this.upcomingGameIds = NO_UPCOMING_GAME_IDS;
		this.playerCount = request.getPlayerCount();
		this.playerCountMin = request.getPlayerCountMin();
		this.playerCountMax = request.getPlayerCountMax();
		this.playerCountExact = request.isPlayerCountExact();
		this.exclusivePlayerCounts = distinctValues(request.getExclusivePlayerCount());
		this.playTimes = distinctValues(request.getPlayTime());
		this.youngestPlayerAge = request.getYoungestPlayerAge();
		this.complexityMin = request.getComplexityMin();
		this.complexityMax = request.getComplexityMax();
		this.playedFilter = request.getPlayedFilter();
		this.currentUserId = null;
		this.mechanisms = distinctValues(request.getMechanism());
		this.mechanismMatch = request.getMechanismMatch();
		this.categories = distinctValues(request.getCategory());
		this.themes = distinctValues(request.getTheme());
		this.themeMatch = request.getThemeMatch();
		this.recommendedPlayerCounts = distinctValues(request.getRecommendedPlayerCount());
		this.bestPlayerCounts = distinctValues(request.getBestPlayerCount());
	}

	private GameListSearchCriteria(
		GameListSearchCriteria source, Collection<Long> upcomingGameIds, Long currentUserId) {
		this.keyword = source.keyword;
		this.upcomingOnly = source.upcomingOnly;
		this.upcomingGameIds = List.copyOf(upcomingGameIds);
		this.playerCount = source.playerCount;
		this.playerCountMin = source.playerCountMin;
		this.playerCountMax = source.playerCountMax;
		this.playerCountExact = source.playerCountExact;
		this.exclusivePlayerCounts = source.exclusivePlayerCounts;
		this.playTimes = source.playTimes;
		this.youngestPlayerAge = source.youngestPlayerAge;
		this.complexityMin = source.complexityMin;
		this.complexityMax = source.complexityMax;
		this.playedFilter = source.playedFilter;
		this.currentUserId = currentUserId;
		this.mechanisms = source.mechanisms;
		this.mechanismMatch = source.mechanismMatch;
		this.categories = source.categories;
		this.themes = source.themes;
		this.themeMatch = source.themeMatch;
		this.recommendedPlayerCounts = source.recommendedPlayerCounts;
		this.bestPlayerCounts = source.bestPlayerCounts;
	}

	private GameListSearchCriteria(GameListSearchCriteria source, String keyword) {
		this.keyword = keyword;
		this.upcomingOnly = source.upcomingOnly;
		this.upcomingGameIds = source.upcomingGameIds;
		this.playerCount = source.playerCount;
		this.playerCountMin = source.playerCountMin;
		this.playerCountMax = source.playerCountMax;
		this.playerCountExact = source.playerCountExact;
		this.exclusivePlayerCounts = source.exclusivePlayerCounts;
		this.playTimes = source.playTimes;
		this.youngestPlayerAge = source.youngestPlayerAge;
		this.complexityMin = source.complexityMin;
		this.complexityMax = source.complexityMax;
		this.playedFilter = source.playedFilter;
		this.currentUserId = source.currentUserId;
		this.mechanisms = source.mechanisms;
		this.mechanismMatch = source.mechanismMatch;
		this.categories = source.categories;
		this.themes = source.themes;
		this.themeMatch = source.themeMatch;
		this.recommendedPlayerCounts = source.recommendedPlayerCounts;
		this.bestPlayerCounts = source.bestPlayerCounts;
	}

	/** 같은 값을 반복 전달해도 한 번 전달한 것과 같도록 빈 값과 중복을 걷어낸다. */
	private static <T> List<T> distinctValues(List<T> values) {
		if (values == null) {
			return List.of();
		}
		return values.stream().filter(Objects::nonNull).distinct().toList();
	}

	public static GameListSearchCriteria from(GameListRequest request) {
		return new GameListSearchCriteria(request);
	}

	public GameListSearchCriteria withUpcomingGameIds(Collection<Long> upcomingGameIds) {
		return new GameListSearchCriteria(this, upcomingGameIds, currentUserId);
	}

	public GameListSearchCriteria withPlayedFilter(long currentUserId) {
		return new GameListSearchCriteria(this, upcomingGameIds, currentUserId);
	}

	public GameListSearchCriteria withKeyword(String keyword) {
		return new GameListSearchCriteria(this, keyword.strip());
	}

	/**
	 * 검색어·필터 조건이 하나도 없는 요청인지 판정한다.
	 *
	 * <p>이 경우에만 저장소 조회에서 전체 건수를 함께 계산한다({@code #1055} 결정). {@code page}·{@code size}는
	 * 조건이 아니므로 판정에 포함하지 않는다.
	 */
	public boolean isFilterless() {
		return keyword == null
			&& !upcomingOnly
			&& playerCount == null
			&& playerCountMin == null
			&& playerCountMax == null
			&& !playerCountExact
			&& exclusivePlayerCounts.isEmpty()
			&& playTimes.isEmpty()
			&& youngestPlayerAge == null
			&& complexityMin == null
			&& complexityMax == null
			&& playedFilter == null
			&& mechanisms.isEmpty()
			&& categories.isEmpty()
			&& themes.isEmpty()
			&& recommendedPlayerCounts.isEmpty()
			&& bestPlayerCounts.isEmpty();
	}

}
