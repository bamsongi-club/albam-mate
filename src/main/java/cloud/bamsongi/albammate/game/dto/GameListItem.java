package cloud.bamsongi.albammate.game.dto;

import java.math.BigDecimal;

import cloud.bamsongi.albammate.game.repository.GameListRow;

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
	long upcomingRoomCount,
	Boolean playedByMe) {

	public GameListItem(
		Long id,
		Long bggId,
		String name,
		String englishName,
		String imageUrl,
		String supportedPlayerCount,
		String tag,
		String estimatedPlayTime,
		BigDecimal complexity,
		long upcomingRoomCount) {
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
			upcomingRoomCount,
			null);
	}

	public static GameListItem from(GameListRow game, long upcomingRoomCount) {
		return from(game, upcomingRoomCount, null);
	}

	public static GameListItem from(GameListRow game, long upcomingRoomCount, Boolean playedByMe) {
		return new GameListItem(
			game.id(),
			game.bggId(),
			game.name(),
			game.englishName(),
			game.imageUrl(),
			game.supportedPlayerCount(),
			game.tag(),
			game.estimatedPlayTime(),
			game.complexity(),
			upcomingRoomCount,
			playedByMe);
	}
}
