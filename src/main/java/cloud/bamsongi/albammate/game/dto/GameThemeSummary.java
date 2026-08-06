package cloud.bamsongi.albammate.game.dto;

import cloud.bamsongi.albammate.game.repository.GameThemeSummaryRow;

public record GameThemeSummary(String code, String nameKo, String nameEn) {
	public static GameThemeSummary from(GameThemeSummaryRow row) {
		return new GameThemeSummary(row.code(), row.nameKo(), row.nameEn());
	}
}
