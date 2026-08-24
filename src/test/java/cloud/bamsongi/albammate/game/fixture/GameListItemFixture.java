package cloud.bamsongi.albammate.game.fixture;

import java.math.BigDecimal;

import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.entity.Game;

public final class GameListItemFixture {

	private GameListItemFixture() {}

	public static GameListItem of(
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
		return new GameListItem(
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
			null);
	}

	public static GameListItem from(Game game, long upcomingRoomCount) {
		return GameListItem.from(game, upcomingRoomCount, null);
	}
}
