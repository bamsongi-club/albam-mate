package cloud.bamsongi.albammate.game.repository;

import java.math.BigDecimal;

import cloud.bamsongi.albammate.game.entity.Game;

/** 게임 목록 응답 조립에 필요한 필드만 담는 내부 값 객체다. */
public record GameListRow(
	Long id,
	Long bggId,
	String name,
	String englishName,
	String imageUrl,
	String supportedPlayerCount,
	String tag,
	String estimatedPlayTime,
	BigDecimal complexity) {

	public static GameListRow from(Game game) {
		return new GameListRow(
			game.getId(),
			game.getBggId(),
			game.getName(),
			game.getEnglishName(),
			game.getImageUrl(),
			game.getSupportedPlayerCount(),
			game.getTag(),
			game.getEstimatedPlayTime(),
			game.getComplexity());
	}
}
