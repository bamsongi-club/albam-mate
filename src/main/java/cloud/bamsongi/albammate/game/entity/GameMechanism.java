package cloud.bamsongi.albammate.game.entity;

import java.sql.Types;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;

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

/** 검수 상태와 표시명을 분리한 게임 메커니즘 내부 목록이다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "game_mechanisms")
public class GameMechanism extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "bgg_mechanism_id", nullable = false, unique = true)
	private Long bggMechanismId;

	@Column(name = "code", nullable = false, unique = true, length = 64)
	private String code;

	@Column(name = "name_ko", nullable = false, length = 100)
	private String nameKo;

	@Column(name = "name_en", nullable = false, length = 100)
	private String nameEn;

	@Column(name = "description_ko", length = 300)
	private String descriptionKo;

	@JdbcTypeCode(Types.SMALLINT)
	@Column(name = "featured_order", unique = true)
	private Integer featuredOrder;

	@Column(name = "is_public", nullable = false)
	private boolean isPublic;

	@Column(name = "source_reference", nullable = false, length = 500)
	private String sourceReference;

	@Column(name = "reviewed_by", length = 100)
	private String reviewedBy;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	public GameMechanism(
		Long bggMechanismId,
		String code,
		String nameKo,
		String nameEn,
		Integer featuredOrder,
		boolean isPublic,
		String sourceReference,
		String reviewedBy,
		Instant reviewedAt) {
		this.bggMechanismId = bggMechanismId;
		this.code = code;
		this.nameKo = nameKo;
		this.nameEn = nameEn;
		this.featuredOrder = featuredOrder;
		this.isPublic = isPublic;
		this.sourceReference = sourceReference;
		this.reviewedBy = reviewedBy;
		this.reviewedAt = reviewedAt;
	}

	public GameMechanism(
		Long bggMechanismId,
		String code,
		String nameKo,
		String nameEn,
		String descriptionKo,
		Integer featuredOrder,
		boolean isPublic,
		String sourceReference,
		String reviewedBy,
		Instant reviewedAt) {
		this(bggMechanismId, code, nameKo, nameEn, featuredOrder, isPublic, sourceReference, reviewedBy, reviewedAt);
		this.descriptionKo = descriptionKo;
	}
}
