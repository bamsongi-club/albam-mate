package cloud.bamsongi.albammate.game.dto;

import cloud.bamsongi.albammate.game.entity.Game;

public record GameRankingItem(int rank, Long gameId, Long bggId, String name, String imageUrl, long roomCount) {

	public static GameRankingItem from(int rank, Game game, long roomCount) {
		return new GameRankingItem(rank, game.getId(), game.getBggId(), game.getName(), game.getImageUrl(), roomCount);
	}
}
