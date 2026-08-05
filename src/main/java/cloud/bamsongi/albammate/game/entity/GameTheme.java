package cloud.bamsongi.albammate.game.entity;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "game_themes")
public class GameTheme extends BaseEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "bgg_theme_id", nullable = false, unique = true)
	private Long bggThemeId;
	@Column(nullable = false, unique = true, length = 64)
	private String code;
	@Column(name = "name_ko", nullable = false, length = 100)
	private String nameKo;
	@Column(name = "name_en", nullable = false, length = 100)
	private String nameEn;

	public GameTheme(Long bggThemeId, String code, String nameKo, String nameEn) {
		this.bggThemeId = bggThemeId;
		this.code = code;
		this.nameKo = nameKo;
		this.nameEn = nameEn;
	}
}
