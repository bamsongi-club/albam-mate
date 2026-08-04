package cloud.bamsongi.albammate.game.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 게임과 메커니즘을 한 번만 연결하는 다대다 관계 행이다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "game_mechanism_relations")
public class GameMechanismRelation {

	@EmbeddedId
	private GameMechanismRelationId id;

	@MapsId("gameId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "game_id", nullable = false)
	private Game game;

	@MapsId("mechanismId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mechanism_id", nullable = false)
	private GameMechanism mechanism;

	public GameMechanismRelation(Game game, GameMechanism mechanism) {
		this.game = game;
		this.mechanism = mechanism;
		this.id = new GameMechanismRelationId(game.getId(), mechanism.getId());
	}
}
