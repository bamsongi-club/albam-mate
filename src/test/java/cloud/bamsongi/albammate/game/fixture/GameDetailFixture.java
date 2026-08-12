package cloud.bamsongi.albammate.game.fixture;

import java.math.BigDecimal;
import java.util.List;

import cloud.bamsongi.albammate.game.dto.GameDetail;
import cloud.bamsongi.albammate.game.entity.Game;

public final class GameDetailFixture {

	private GameDetailFixture() {}

	public static GameDetail of(
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
		return new GameDetail(
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

	public static GameDetail of(
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
		return new GameDetail(
			id,
			bggId,
			name,
			englishName,
			imageUrl,
			supportedPlayerCount,
			tag,
			estimatedPlayTime,
			complexity,
			releaseYear,
			null,
			upcomingRoomCount,
			alias,
			description,
			detailDescription,
			playedByMe,
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			List.of());
	}

	public static GameDetail from(Game game, long upcomingRoomCount) {
		return GameDetail.from(game, upcomingRoomCount, null, List.of(), List.of(), List.of(), List.of(), List.of());
	}
}
