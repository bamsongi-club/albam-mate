package cloud.bamsongi.albammate.game.entity;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "game_categories")
public class GameCategory extends BaseEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, unique = true, length = 64)
	private String code;
	@Column(name = "name_ko", nullable = false, length = 100)
	private String nameKo;
	@Column(name = "name_en", nullable = false, length = 100)
	private String nameEn;
	@Column(name = "bgg_subdomain", nullable = false, unique = true, length = 64)
	private String bggSubdomain;
	@Column(name = "display_order", nullable = false)
	private Integer displayOrder;

	public GameCategory(String code, String nameKo, String nameEn, String bggSubdomain, Integer displayOrder) {
		this.code = code;
		this.nameKo = nameKo;
		this.nameEn = nameEn;
		this.bggSubdomain = bggSubdomain;
		this.displayOrder = displayOrder;
	}
}
