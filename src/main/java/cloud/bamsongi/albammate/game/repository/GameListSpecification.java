package cloud.bamsongi.albammate.game.repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.dto.MechanismMatch;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
import cloud.bamsongi.albammate.game.dto.ThemeMatch;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.entity.GameCategoryRelation;
import cloud.bamsongi.albammate.game.entity.GameMechanismRelation;
import cloud.bamsongi.albammate.game.entity.GamePlayerPreference;
import cloud.bamsongi.albammate.game.entity.GameThemeRelation;
import cloud.bamsongi.albammate.game.entity.UserPlayedGame;
import cloud.bamsongi.albammate.game.service.GameListSearchCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/** 게임 목록 검색 조건을 JPA Specification으로 조립한다. */
public final class GameListSpecification {

	private static final int PLAYER_COUNT_TEN_OR_MORE = 10;

	private GameListSpecification() {}

	public static Specification<Game> from(GameListSearchCriteria criteria) {
		return (root, query, criteriaBuilder) -> {
			List<Predicate> predicates = new ArrayList<>();
			String keyword = criteria.getKeyword();
			if (keyword != null) {
				predicates.add(
					criteriaBuilder.like(
						criteriaBuilder.lower(root.get("name")),
						"%" + escapeLikePattern(keyword.toLowerCase(Locale.ROOT)) + "%",
						'\\'));
			}
			if (criteria.isUpcomingOnly()) {
				predicates.add(root.get("id").in(criteria.getUpcomingGameIds()));
			}
			addPlayerCountPredicates(root, criteriaBuilder, predicates, criteria);
			addPlayTimePredicate(root, criteriaBuilder, predicates, criteria.getPlayTimes());
			addYoungestPlayerAgePredicate(root, criteriaBuilder, predicates, criteria.getYoungestPlayerAge());
			BigDecimal complexityMin = criteria.getComplexityMin();
			if (complexityMin != null) {
				predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("complexity"), complexityMin));
			}
			BigDecimal complexityMax = criteria.getComplexityMax();
			if (complexityMax != null) {
				predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("complexity"), complexityMax));
			}
			addMechanismPredicate(
				root, query, criteriaBuilder, predicates, criteria.getMechanisms(), criteria.getMechanismMatch());
			addCategoryPredicate(root, query, criteriaBuilder, predicates, criteria.getCategories());
			addThemePredicate(root, query, criteriaBuilder, predicates, criteria.getThemes(), criteria.getThemeMatch());
			addPlayerPreferencePredicate(
				root, query, criteriaBuilder, predicates, criteria.getRecommendedPlayerCounts(), true);
			addPlayerPreferencePredicate(root, query, criteriaBuilder, predicates, criteria.getBestPlayerCounts(),
				false);
			addPlayedGamePredicate(root, query, criteriaBuilder, predicates, criteria.getPlayedFilter(),
				criteria.getCurrentUserId());
			return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
		};
	}

	private static void addCategoryPredicate(
		Root<Game> root,
		jakarta.persistence.criteria.CriteriaQuery<?> query,
		CriteriaBuilder criteriaBuilder,
		List<Predicate> predicates,
		List<String> categories) {
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

	private static void addThemePredicate(
		Root<Game> root,
		jakarta.persistence.criteria.CriteriaQuery<?> query,
		CriteriaBuilder criteriaBuilder,
		List<Predicate> predicates,
		List<String> themes,
		ThemeMatch themeMatch) {
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

	private static void addPlayerPreferencePredicate(
		Root<Game> root,
		jakarta.persistence.criteria.CriteriaQuery<?> query,
		CriteriaBuilder criteriaBuilder,
		List<Predicate> predicates,
		List<Integer> playerCounts,
		boolean recommended) {
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

	private static void addPlayedGamePredicate(
		Root<Game> root,
		jakarta.persistence.criteria.CriteriaQuery<?> query,
		CriteriaBuilder criteriaBuilder,
		List<Predicate> predicates,
		PlayedFilter playedFilter,
		Long currentUserId) {
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

	private static void addMechanismPredicate(
		Root<Game> root,
		jakarta.persistence.criteria.CriteriaQuery<?> query,
		CriteriaBuilder criteriaBuilder,
		List<Predicate> predicates,
		List<String> mechanisms,
		MechanismMatch mechanismMatch) {
		if (mechanisms.isEmpty()) {
			return;
		}
		var subquery = query.subquery(Long.class);
		Root<GameMechanismRelation> relation = subquery.from(GameMechanismRelation.class);
		var mechanism = relation.join("mechanism");
		if (mechanismMatch == MechanismMatch.ALL) {
			subquery.select(criteriaBuilder.countDistinct(mechanism.get("code")));
			subquery.where(
				criteriaBuilder.equal(relation.get("game"), root),
				mechanism.get("code").in(mechanisms),
				criteriaBuilder.isTrue(mechanism.get("isPublic")));
			predicates.add(criteriaBuilder.equal(subquery, (long)mechanisms.size()));
			return;
		}
		subquery.select(criteriaBuilder.literal(1L));
		subquery.where(
			criteriaBuilder.equal(relation.get("game"), root),
			mechanism.get("code").in(mechanisms),
			criteriaBuilder.isTrue(mechanism.get("isPublic")));
		predicates.add(criteriaBuilder.exists(subquery));
	}

	private static void addPlayerCountPredicates(
		Root<Game> root,
		CriteriaBuilder criteriaBuilder,
		List<Predicate> predicates,
		GameListSearchCriteria criteria) {
		Integer playerCount = criteria.getPlayerCount();
		if (playerCount != null) {
			if (playerCount == PLAYER_COUNT_TEN_OR_MORE) {
				predicates.add(
					criteriaBuilder.greaterThanOrEqualTo(root.get("maxPlayers"), PLAYER_COUNT_TEN_OR_MORE));
			} else {
				predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("minPlayers"), playerCount));
				predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("maxPlayers"), playerCount));
			}
		}
		Integer playerCountMin = criteria.getPlayerCountMin();
		Integer playerCountMax = criteria.getPlayerCountMax();
		if (criteria.isPlayerCountExact()) {
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
		List<Integer> exclusivePlayerCounts = criteria.getExclusivePlayerCounts();
		if (!exclusivePlayerCounts.isEmpty()) {
			predicates.add(
				criteriaBuilder.or(
					exclusivePlayerCounts.stream()
						.map(value -> exclusivePlayerCountPredicate(root, criteriaBuilder, value))
						.toArray(Predicate[]::new)));
		}
	}

	private static Predicate exclusivePlayerCountPredicate(
		Root<Game> root, CriteriaBuilder criteriaBuilder, Integer value) {
		return criteriaBuilder.and(
			criteriaBuilder.equal(root.get("minPlayers"), value),
			criteriaBuilder.equal(root.get("maxPlayers"), value));
	}

	private static void addPlayTimePredicate(
		Root<Game> root,
		CriteriaBuilder criteriaBuilder,
		List<Predicate> predicates,
		List<GamePlayTimeFilter> playTimes) {
		if (playTimes.isEmpty()) {
			return;
		}
		predicates.add(
			criteriaBuilder.or(
				playTimes.stream()
					.map(playTime -> playTimePredicate(root, criteriaBuilder, playTime))
					.toArray(Predicate[]::new)));
	}

	private static Predicate playTimePredicate(
		Root<Game> root, CriteriaBuilder criteriaBuilder, GamePlayTimeFilter playTime) {
		List<Predicate> bounds = new ArrayList<>();
		if (playTime.getMinInclusive() != null) {
			bounds.add(
				criteriaBuilder.greaterThanOrEqualTo(root.get("maxPlayTimeMinutes"), playTime.getMinInclusive()));
		}
		if (playTime.getMaxInclusive() != null) {
			bounds.add(
				criteriaBuilder.lessThanOrEqualTo(root.get("maxPlayTimeMinutes"), playTime.getMaxInclusive()));
		}
		return criteriaBuilder.and(bounds.toArray(Predicate[]::new));
	}

	private static void addYoungestPlayerAgePredicate(
		Root<Game> root,
		CriteriaBuilder criteriaBuilder,
		List<Predicate> predicates,
		Integer youngestPlayerAge) {
		if (youngestPlayerAge == null) {
			return;
		}
		predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("minAge"), youngestPlayerAge));
	}

	private static String escapeLikePattern(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
