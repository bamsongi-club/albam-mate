package cloud.bamsongi.albammate.game.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import cloud.bamsongi.albammate.game.dto.GameAgeBandFilter;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
import cloud.bamsongi.albammate.game.dto.ThemeMatch;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.entity.GameCategoryRelation;
import cloud.bamsongi.albammate.game.entity.GameMechanismRelation;
import cloud.bamsongi.albammate.game.entity.GamePlayerPreference;
import cloud.bamsongi.albammate.game.entity.GameThemeRelation;
import cloud.bamsongi.albammate.game.entity.UserPlayedGame;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.Getter;

/** 게임 목록의 선택 조건을 하나의 저장소 조회 경계로 전달하는 불변 값 객체다. */
@Getter
public final class GameListSearchCriteria {

	private static final List<Long> NO_UPCOMING_GAME_IDS = List.of(-1L);
	private static final int PLAYER_COUNT_TEN_OR_MORE = 10;

	private final String keyword;
	private final boolean upcomingOnly;
	private final Collection<Long> upcomingGameIds;
	private final Integer playerCount;
	private final Integer playerCountMin;
	private final Integer playerCountMax;
	private final boolean playerCountExact;
	private final List<Integer> exclusivePlayerCounts;
	private final List<GamePlayTimeFilter> playTimes;
	private final List<GameAgeBandFilter> ageBands;
	private final BigDecimal complexityMin;
	private final BigDecimal complexityMax;
	private final PlayedFilter playedFilter;
	private final Long currentUserId;
	private final List<String> mechanisms;
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
		this.ageBands = distinctValues(request.getAgeBand());
		this.complexityMin = request.getComplexityMin();
		this.complexityMax = request.getComplexityMax();
		this.playedFilter = request.getPlayedFilter();
		this.currentUserId = null;
		this.mechanisms = distinctValues(request.getMechanism());
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
		this.ageBands = source.ageBands;
		this.complexityMin = source.complexityMin;
		this.complexityMax = source.complexityMax;
		this.playedFilter = source.playedFilter;
		this.currentUserId = currentUserId;
		this.mechanisms = source.mechanisms;
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

	public static GameListSearchCriteria keywordOnly(String keyword) {
		GameListRequest request = new GameListRequest();
		request.setKeyword(keyword);
		return from(request);
	}

	public GameListSearchCriteria withUpcomingGameIds(Collection<Long> upcomingGameIds) {
		return new GameListSearchCriteria(this, upcomingGameIds, currentUserId);
	}

	public GameListSearchCriteria withPlayedFilter(long currentUserId) {
		return new GameListSearchCriteria(this, upcomingGameIds, currentUserId);
	}

	public Specification<Game> toSpecification() {
		return (root, query, criteriaBuilder) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (keyword != null) {
				predicates.add(
					criteriaBuilder.like(
						criteriaBuilder.lower(root.get("name")),
						"%" + escapeLikePattern(keyword.toLowerCase(Locale.ROOT)) + "%",
						'\\'));
			}
			if (upcomingOnly) {
				predicates.add(root.get("id").in(upcomingGameIds));
			}
			addPlayerCountPredicates(root, criteriaBuilder, predicates);
			addPlayTimePredicate(root, criteriaBuilder, predicates);
			addAgeBandPredicate(root, criteriaBuilder, predicates);
			if (complexityMin != null) {
				predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("complexity"), complexityMin));
			}
			if (complexityMax != null) {
				predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("complexity"), complexityMax));
			}
			addMechanismPredicate(root, query, criteriaBuilder, predicates);
			addCategoryPredicate(root, query, criteriaBuilder, predicates);
			addThemePredicate(root, query, criteriaBuilder, predicates);
			addPlayerPreferencePredicate(root, query, criteriaBuilder, predicates, recommendedPlayerCounts, true);
			addPlayerPreferencePredicate(root, query, criteriaBuilder, predicates, bestPlayerCounts, false);
			addPlayedGamePredicate(root, query, criteriaBuilder, predicates);
			return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
		};
	}

	private void addCategoryPredicate(Root<Game> root, jakarta.persistence.criteria.CriteriaQuery<?> query,
		CriteriaBuilder criteriaBuilder, List<Predicate> predicates) {
		if (categories.isEmpty()) {
			return;
		}
		var subquery = query.subquery(Long.class);
		Root<GameCategoryRelation> relation = subquery.from(GameCategoryRelation.class);
		subquery.select(criteriaBuilder.literal(1L));
		subquery.where(criteriaBuilder.equal(relation.get("game"), root),
			relation.join("category").get("code").in(categories));
		predicates.add(criteriaBuilder.exists(subquery));
	}

	private void addThemePredicate(Root<Game> root, jakarta.persistence.criteria.CriteriaQuery<?> query,
		CriteriaBuilder criteriaBuilder, List<Predicate> predicates) {
		if (themes.isEmpty()) {
			return;
		}
		var subquery = query.subquery(Long.class);
		Root<GameThemeRelation> relation = subquery.from(GameThemeRelation.class);
		var theme = relation.join("theme");
		if (themeMatch == ThemeMatch.ALL) {
			subquery.select(criteriaBuilder.countDistinct(theme.get("code")));
			subquery.where(criteriaBuilder.equal(relation.get("game"), root), theme.get("code").in(themes));
			predicates.add(criteriaBuilder.equal(subquery, (long)themes.size()));
			return;
		}
		subquery.select(criteriaBuilder.literal(1L));
		subquery.where(criteriaBuilder.equal(relation.get("game"), root), theme.get("code").in(themes));
		predicates.add(criteriaBuilder.exists(subquery));
	}

	private void addPlayerPreferencePredicate(Root<Game> root, jakarta.persistence.criteria.CriteriaQuery<?> query,
		CriteriaBuilder criteriaBuilder, List<Predicate> predicates, List<Integer> playerCounts, boolean recommended) {
		if (playerCounts.isEmpty()) {
			return;
		}
		var subquery = query.subquery(Long.class);
		Root<GamePlayerPreference> preference = subquery.from(GamePlayerPreference.class);
		subquery.select(criteriaBuilder.literal(1L));
		subquery.where(
			criteriaBuilder.equal(preference.get("game"), root),
			preference.get("id").get("playerCount").in(playerCounts),
			recommended ? criteriaBuilder.isTrue(preference.get("isRecommended"))
				: criteriaBuilder.isTrue(preference.get("isBest")));
		predicates.add(criteriaBuilder.exists(subquery));
	}

	/** 현재 사용자의 표시 관계를 EXISTS 또는 NOT EXISTS로 적용한다. */
	private void addPlayedGamePredicate(
		Root<Game> root,
		jakarta.persistence.criteria.CriteriaQuery<?> query,
		CriteriaBuilder criteriaBuilder,
		List<Predicate> predicates) {
		if (playedFilter == null) {
			return;
		}
		var subquery = query.subquery(Long.class);
		Root<UserPlayedGame> relation = subquery.from(UserPlayedGame.class);
		subquery.select(criteriaBuilder.literal(1L));
		subquery.where(
			criteriaBuilder.equal(relation.get("userId"), currentUserId),
			criteriaBuilder.equal(relation.get("gameId"), root.get("id")));
		Predicate exists = criteriaBuilder.exists(subquery);
		predicates.add(playedFilter == PlayedFilter.PLAYED_ONLY ? exists : criteriaBuilder.not(exists));
	}

	/** 선택한 공개 메커니즘 중 하나와 관계가 있는 게임만 EXISTS로 남긴다. */
	private void addMechanismPredicate(
		Root<Game> root,
		jakarta.persistence.criteria.CriteriaQuery<?> query,
		CriteriaBuilder criteriaBuilder,
		List<Predicate> predicates) {
		if (mechanisms.isEmpty()) {
			return;
		}
		var subquery = query.subquery(Long.class);
		Root<GameMechanismRelation> relation = subquery.from(GameMechanismRelation.class);
		var mechanism = relation.join("mechanism");
		subquery.select(criteriaBuilder.literal(1L));
		subquery.where(
			criteriaBuilder.equal(relation.get("game"), root),
			mechanism.get("code").in(mechanisms),
			criteriaBuilder.isTrue(mechanism.get("isPublic")));
		predicates.add(criteriaBuilder.exists(subquery));
	}

	/**
	 * 인원 조건을 판정한다.
	 *
	 * <p>범위 조건은 요청 범위 전체를 지원하는 게임을, 경계 정확 일치는 전달한 경계와 같은 게임을 찾는다.
	 * 전용 인원은 선택한 값끼리 OR로 결합한다. 검증값이 {@code NULL}인 게임은 어느 판정에도 걸리지 않아
	 * 조건을 적용할 때 자연히 제외된다.
	 */
	private void addPlayerCountPredicates(
		Root<Game> root, CriteriaBuilder criteriaBuilder, List<Predicate> predicates) {
		if (playerCount != null) {
			if (playerCount == PLAYER_COUNT_TEN_OR_MORE) {
				predicates.add(
					criteriaBuilder.greaterThanOrEqualTo(root.get("maxPlayers"), PLAYER_COUNT_TEN_OR_MORE));
			} else {
				predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("minPlayers"), playerCount));
				predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("maxPlayers"), playerCount));
			}
		}
		if (playerCountExact) {
			if (playerCountMin != null) {
				predicates.add(criteriaBuilder.equal(root.get("minPlayers"), playerCountMin));
			}
			if (playerCountMax != null) {
				predicates.add(criteriaBuilder.equal(root.get("maxPlayers"), playerCountMax));
			}
		} else if (playerCountMin != null && playerCountMax != null) {
			predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("minPlayers"), playerCountMin));
			predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("maxPlayers"), playerCountMax));
		} else if (playerCountMin != null) {
			predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("maxPlayers"), playerCountMin));
		} else if (playerCountMax != null) {
			predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("minPlayers"), playerCountMax));
		}
		if (!exclusivePlayerCounts.isEmpty()) {
			predicates.add(
				criteriaBuilder.or(
					exclusivePlayerCounts.stream()
						.map(value -> exclusivePlayerCountPredicate(root, criteriaBuilder, value))
						.toArray(Predicate[]::new)));
		}
	}

	private Predicate exclusivePlayerCountPredicate(
		Root<Game> root, CriteriaBuilder criteriaBuilder, Integer value) {
		return criteriaBuilder.and(
			criteriaBuilder.equal(root.get("minPlayers"), value),
			criteriaBuilder.equal(root.get("maxPlayers"), value));
	}

	/** 선택한 플레이 시간 구간을 OR로 결합하고 각 구간은 검증된 최대 플레이 시간으로 판정한다. */
	private void addPlayTimePredicate(
		Root<Game> root, CriteriaBuilder criteriaBuilder, List<Predicate> predicates) {
		if (playTimes.isEmpty()) {
			return;
		}
		predicates.add(
			criteriaBuilder.or(
				playTimes.stream()
					.map(playTime -> playTimePredicate(root, criteriaBuilder, playTime))
					.toArray(Predicate[]::new)));
	}

	private Predicate playTimePredicate(
		Root<Game> root, CriteriaBuilder criteriaBuilder, GamePlayTimeFilter playTime) {
		List<Predicate> bounds = new ArrayList<>();
		if (playTime.getMinInclusive() != null) {
			bounds.add(
				criteriaBuilder.greaterThanOrEqualTo(
					root.get("maxPlayTimeMinutes"), playTime.getMinInclusive()));
		}
		if (playTime.getMaxInclusive() != null) {
			bounds.add(
				criteriaBuilder.lessThanOrEqualTo(
					root.get("maxPlayTimeMinutes"), playTime.getMaxInclusive()));
		}
		return criteriaBuilder.and(bounds.toArray(Predicate[]::new));
	}

	/** 선택한 연령대 구간을 OR로 결합하고 각 구간은 {@code minAge}로 판정한다. */
	private void addAgeBandPredicate(
		Root<Game> root, CriteriaBuilder criteriaBuilder, List<Predicate> predicates) {
		if (ageBands.isEmpty()) {
			return;
		}
		predicates.add(
			criteriaBuilder.or(
				ageBands.stream()
					.map(ageBand -> ageBandPredicate(root, criteriaBuilder, ageBand))
					.toArray(Predicate[]::new)));
	}

	private Predicate ageBandPredicate(
		Root<Game> root, CriteriaBuilder criteriaBuilder, GameAgeBandFilter ageBand) {
		List<Predicate> bounds = new ArrayList<>();
		if (ageBand.getMinInclusive() != null) {
			bounds.add(criteriaBuilder.greaterThanOrEqualTo(root.get("minAge"), ageBand.getMinInclusive()));
		}
		if (ageBand.getMaxInclusive() != null) {
			bounds.add(criteriaBuilder.lessThanOrEqualTo(root.get("minAge"), ageBand.getMaxInclusive()));
		}
		return criteriaBuilder.and(bounds.toArray(Predicate[]::new));
	}

	private String escapeLikePattern(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
