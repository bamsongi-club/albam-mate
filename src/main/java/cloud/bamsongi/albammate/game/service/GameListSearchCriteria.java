package cloud.bamsongi.albammate.game.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.entity.Game;
import jakarta.persistence.criteria.Predicate;
import lombok.Getter;

/** 게임 목록의 선택 조건을 하나의 저장소 조회 경계로 전달하는 불변 값 객체다. */
@Getter
public final class GameListSearchCriteria {

	private static final List<Long> NO_UPCOMING_GAME_IDS = List.of(-1L);

	private final String keyword;
	private final boolean upcomingOnly;
	private final Collection<Long> upcomingGameIds;
	private final Integer playerCount;
	private final Integer playTimeMinExclusive;
	private final Integer playTimeMaxInclusive;
	private final BigDecimal complexityMin;
	private final BigDecimal complexityMax;

	private GameListSearchCriteria(
		String keyword,
		boolean upcomingOnly,
		Collection<Long> upcomingGameIds,
		Integer playerCount,
		Integer playTimeMinExclusive,
		Integer playTimeMaxInclusive,
		BigDecimal complexityMin,
		BigDecimal complexityMax) {
		this.keyword = StringUtils.hasText(keyword) ? keyword.strip() : null;
		this.upcomingOnly = upcomingOnly;
		this.upcomingGameIds = upcomingGameIds == null ? NO_UPCOMING_GAME_IDS : List.copyOf(upcomingGameIds);
		this.playerCount = playerCount;
		this.playTimeMinExclusive = playTimeMinExclusive;
		this.playTimeMaxInclusive = playTimeMaxInclusive;
		this.complexityMin = complexityMin;
		this.complexityMax = complexityMax;
	}

	public static GameListSearchCriteria from(GameListRequest request) {
		GamePlayTimeFilter playTime = request.getPlayTime();
		return new GameListSearchCriteria(
			request.getKeyword(),
			request.isUpcomingOnly(),
			null,
			request.getPlayerCount(),
			playTime == null ? null : playTime.getMinExclusive(),
			playTime == null ? null : playTime.getMaxInclusive(),
			request.getComplexityMin(),
			request.getComplexityMax());
	}

	public static GameListSearchCriteria keywordOnly(String keyword) {
		return new GameListSearchCriteria(keyword, false, null, null, null, null, null, null);
	}

	public GameListSearchCriteria withUpcomingGameIds(Collection<Long> upcomingGameIds) {
		return new GameListSearchCriteria(
			keyword,
			upcomingOnly,
			upcomingGameIds,
			playerCount,
			playTimeMinExclusive,
			playTimeMaxInclusive,
			complexityMin,
			complexityMax);
	}

	public Specification<Game> toSpecification() {
		return (root, query, criteriaBuilder) -> {
			List<Predicate> predicates = new java.util.ArrayList<>();
			if (keyword != null) {
				predicates.add(
					criteriaBuilder.like(
						criteriaBuilder.lower(root.get("name")),
						"%" + escapeLikePattern(keyword.toLowerCase(java.util.Locale.ROOT)) + "%",
						'\\'));
			}
			if (upcomingOnly) {
				predicates.add(root.get("id").in(upcomingGameIds));
			}
			if (playerCount != null) {
				if (playerCount == 10) {
					predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("maxPlayers"), 10));
				} else {
					predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("minPlayers"), playerCount));
					predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("maxPlayers"), playerCount));
				}
			}
			if (playTimeMinExclusive != null) {
				predicates.add(
					criteriaBuilder.greaterThan(root.get("maxPlayTimeMinutes"), playTimeMinExclusive));
			}
			if (playTimeMaxInclusive != null) {
				predicates.add(
					criteriaBuilder.lessThanOrEqualTo(root.get("maxPlayTimeMinutes"), playTimeMaxInclusive));
			}
			if (complexityMin != null) {
				predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("complexity"), complexityMin));
			}
			if (complexityMax != null) {
				predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("complexity"), complexityMax));
			}
			return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
		};
	}

	private String escapeLikePattern(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
