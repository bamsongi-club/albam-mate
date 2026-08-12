package cloud.bamsongi.albammate.game.dto;

import cloud.bamsongi.albammate.game.entity.Game;

public record GameRankingItem(
	int rank,
	Long gameId,
	Long bggId,
	String name,
	String englishName,
	Integer releaseYear,
	String imageUrl,
	String description,
	long roomCount) {

	public static GameRankingItem from(int rank, Game game, long roomCount) {
		return new GameRankingItem(
			rank,
			game.getId(),
			game.getBggId(),
			game.getName(),
			game.getEnglishName(),
			game.getReleaseYear(),
			game.getImageUrl(),
			game.getDescription(),
			roomCount);
	}
}
