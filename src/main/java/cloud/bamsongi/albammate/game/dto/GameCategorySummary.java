package cloud.bamsongi.albammate.game.dto;

import cloud.bamsongi.albammate.game.repository.GameCategorySummaryRow;

public record GameCategorySummary(String code, String nameKo, String nameEn) {
	public static GameCategorySummary from(GameCategorySummaryRow row) {
		return new GameCategorySummary(row.code(), row.nameKo(), row.nameEn());
	}
}
