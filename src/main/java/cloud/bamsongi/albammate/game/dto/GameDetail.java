package cloud.bamsongi.albammate.game.dto;

import java.math.BigDecimal;

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
	long upcomingRoomCount,
	String alias,
	String description,
	String detailDescription) {

	public static GameDetail from(Game game, long upcomingRoomCount) {
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
			upcomingRoomCount,
			game.getAlias(),
			game.getDescription(),
			game.getDetailDescription());
	}
}
