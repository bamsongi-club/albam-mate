package cloud.bamsongi.albammate.game.dto;

import cloud.bamsongi.albammate.game.repository.GameMechanismOptionRow;

/** 공개 게임 메커니즘 선택지 응답이다. */
public record GameMechanismOption(
	String code,
	String nameKo,
	String nameEn,
	Integer featuredOrder) {

	public static GameMechanismOption from(GameMechanismOptionRow row) {
		return new GameMechanismOption(row.code(), row.nameKo(), row.nameEn(), row.featuredOrder());
	}
}
