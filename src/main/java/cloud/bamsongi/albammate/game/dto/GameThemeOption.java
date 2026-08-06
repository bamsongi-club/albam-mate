package cloud.bamsongi.albammate.game.dto;

import cloud.bamsongi.albammate.game.repository.GameThemeOptionRow;

public record GameThemeOption(String code, String nameKo, String nameEn) {
	public static GameThemeOption from(GameThemeOptionRow row) {
		return new GameThemeOption(row.code(), row.nameKo(), row.nameEn());
	}
}
