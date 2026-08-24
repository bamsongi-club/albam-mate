package cloud.bamsongi.albammate.game.dto;

import cloud.bamsongi.albammate.game.repository.GameMechanismSummaryRow;

/** 게임 상세에 포함하는 공개 메커니즘 요약이다. */
public record GameMechanismSummary(String code, String nameKo, String nameEn) {

	public static GameMechanismSummary from(GameMechanismSummaryRow row) {
		return new GameMechanismSummary(row.code(), row.nameKo(), row.nameEn());
	}
}
