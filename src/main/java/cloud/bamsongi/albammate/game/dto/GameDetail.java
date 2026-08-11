package cloud.bamsongi.albammate.game.dto;

import java.math.BigDecimal;
import java.util.List;

import cloud.bamsongi.albammate.game.entity.Game;

public record GameDetail(
	Long id,
	Long bggId,
	String name,
	String englishName,
	String imageUrl,
	String supportedPlayerCount,
	String tag,
	String estimatedPlayTime,
	BigDecimal complexity,
	Integer releaseYear,
	Integer minAge,
	long upcomingRoomCount,
	String alias,
	String description,
	String detailDescription,
	Boolean playedByMe,
	List<GameCategorySummary> categories,
	List<GameThemeSummary> themes,
	List<Integer> recommendedPlayerCounts,
	List<Integer> bestPlayerCounts,
	List<GameMechanismSummary> mechanisms) {

	public GameDetail(
		Long id,
		Long bggId,
		String name,
		String englishName,
		String imageUrl,
		String supportedPlayerCount,
		String tag,
		String estimatedPlayTime,
		BigDecimal complexity,
		Integer releaseYear,
		long upcomingRoomCount,
		String alias,
		String description,
		String detailDescription,
		Boolean playedByMe) {
		this(id, bggId, name, englishName, imageUrl, supportedPlayerCount, tag, estimatedPlayTime,
			complexity, releaseYear, null, upcomingRoomCount, alias, description, detailDescription, playedByMe,
			List.of(), List.of(), List.of(), List.of(), List.of());
	}

	public GameDetail(
		Long id,
		Long bggId,
		String name,
		String englishName,
		String imageUrl,
		String supportedPlayerCount,
		String tag,
		String estimatedPlayTime,
		BigDecimal complexity,
		long upcomingRoomCount,
		String alias,
		String description,
		String detailDescription) {
		this(
			id,
			bggId,
			name,
			englishName,
			imageUrl,
			supportedPlayerCount,
			tag,
			estimatedPlayTime,
			complexity,
			null,
			null,
			upcomingRoomCount,
			alias,
			description,
			detailDescription,
			null,
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			List.of());
	}

	public GameDetail(
		Long id,
		Long bggId,
		String name,
		String englishName,
		String imageUrl,
		String supportedPlayerCount,
		String tag,
		String estimatedPlayTime,
		BigDecimal complexity,
		Integer releaseYear,
		Integer minAge,
		long upcomingRoomCount,
		String alias,
		String description,
		String detailDescription,
		Boolean playedByMe,
		List<GameCategorySummary> categories,
		List<GameThemeSummary> themes,
		List<Integer> recommendedPlayerCounts,
		List<Integer> bestPlayerCounts) {
		this(id, bggId, name, englishName, imageUrl, supportedPlayerCount, tag, estimatedPlayTime, complexity,
			releaseYear, minAge, upcomingRoomCount, alias, description, detailDescription, playedByMe, categories,
			themes, recommendedPlayerCounts, bestPlayerCounts, List.of());
	}

	public static GameDetail from(Game game, long upcomingRoomCount) {
		return from(game, upcomingRoomCount, null);
	}

	public static GameDetail from(Game game, long upcomingRoomCount, Boolean playedByMe) {
		return from(game, upcomingRoomCount, playedByMe, List.of(), List.of(), List.of(), List.of(), List.of());
	}

	public static GameDetail from(
		Game game,
		long upcomingRoomCount,
		Boolean playedByMe,
		List<GameCategorySummary> categories,
		List<GameThemeSummary> themes,
		List<Integer> recommendedPlayerCounts,
		List<Integer> bestPlayerCounts) {
		return from(game, upcomingRoomCount, playedByMe, categories, themes, recommendedPlayerCounts,
			bestPlayerCounts, List.of());
	}

	public static GameDetail from(
		Game game,
		long upcomingRoomCount,
		Boolean playedByMe,
		List<GameCategorySummary> categories,
		List<GameThemeSummary> themes,
		List<Integer> recommendedPlayerCounts,
		List<Integer> bestPlayerCounts,
		List<GameMechanismSummary> mechanisms) {
		return new GameDetail(
			game.getId(),
			game.getBggId(),
			game.getName(),
			game.getEnglishName(),
			game.getImageUrl(),
			game.getSupportedPlayerCount(),
			game.getTag(),
			game.getEstimatedPlayTime(),
			game.getComplexity(),
			game.getReleaseYear(),
			game.getMinAge(),
			upcomingRoomCount,
			game.getAlias(),
			game.getDescription(),
			game.getDetailDescription(),
			playedByMe,
			List.copyOf(categories),
			List.copyOf(themes),
			List.copyOf(recommendedPlayerCounts),
			List.copyOf(bestPlayerCounts),
			List.copyOf(mechanisms));
	}
}
