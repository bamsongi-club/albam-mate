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
	long upcomingRoomCount) {

	/**
	 * 게임 목록 조회 행과 예정 모임 수로 목록 응답을 생성한다.
	 *
	 * @param game 게임 목록 조회 행
	 * @param upcomingRoomCount 조회 시각 기준 예정 모임 수
	 * @return 예정 모임 수가 포함된 게임 목록 응답
	 */
	public static GameListItem from(GameListRow game, long upcomingRoomCount) {
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
			upcomingRoomCount);
	}
}
