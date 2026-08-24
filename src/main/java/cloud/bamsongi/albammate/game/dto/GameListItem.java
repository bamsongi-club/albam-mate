package cloud.bamsongi.albammate.game.dto;

import java.math.BigDecimal;

import cloud.bamsongi.albammate.game.entity.Game;

public record GameListItem(
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
	Boolean playedByMe) {

	public static GameListItem from(Game game, long upcomingRoomCount, Boolean playedByMe) {
		return new GameListItem(
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
			playedByMe);
	}
}
