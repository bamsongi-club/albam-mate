package cloud.bamsongi.albammate.game.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** 게임과 메커니즘 관계의 복합 식별자다. */
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class GameMechanismRelationId implements Serializable {

	private static final long serialVersionUID = 1L;

	@Column(name = "game_id")
	private Long gameId;

	@Column(name = "mechanism_id")
	private Long mechanismId;
}
