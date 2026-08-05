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

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "game_theme_relations")
public class GameThemeRelation {
	@EmbeddedId
	private GameThemeRelationId id;
	@MapsId("gameId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "game_id")
	private Game game;
	@MapsId("themeId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "theme_id")
	private GameTheme theme;

	public GameThemeRelation(Game game, GameTheme theme) {
		this.game = game;
		this.theme = theme;
		this.id = new GameThemeRelationId(game.getId(), theme.getId());
	}
}
