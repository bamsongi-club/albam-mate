package cloud.bamsongi.albammate.game.dto;

import cloud.bamsongi.albammate.game.repository.GameCategoryOptionRow;

public record GameCategoryOption(String code, String nameKo, String nameEn, Integer displayOrder) {
	public static GameCategoryOption from(GameCategoryOptionRow row) {
		return new GameCategoryOption(row.code(), row.nameKo(), row.nameEn(), row.displayOrder());
	}
}
