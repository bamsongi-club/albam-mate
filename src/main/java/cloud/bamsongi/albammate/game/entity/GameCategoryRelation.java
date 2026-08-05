package cloud.bamsongi.albammate.game.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "game_category_relations")
public class GameCategoryRelation {
	@EmbeddedId
	private GameCategoryRelationId id;
	@MapsId("gameId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "game_id")
	private Game game;
	@MapsId("categoryId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id")
	private GameCategory category;

	public GameCategoryRelation(Game game, GameCategory category) {
		this.game = game;
		this.category = category;
		this.id = new GameCategoryRelationId(game.getId(), category.getId());
	}
}
