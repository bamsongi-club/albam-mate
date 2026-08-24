package cloud.bamsongi.albammate.game.entity;

import jakarta.persistence.Column;
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
@Table(name = "game_player_preferences")
public class GamePlayerPreference {
	@EmbeddedId
	private GamePlayerPreferenceId id;
	@MapsId("gameId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "game_id")
	private Game game;
	@Column(name = "is_recommended", nullable = false)
	private boolean isRecommended;
	@Column(name = "is_best", nullable = false)
	private boolean isBest;

	public GamePlayerPreference(Game game, Integer playerCount, boolean isRecommended, boolean isBest) {
		if (isBest && !isRecommended) {
			throw new IllegalArgumentException("best requires recommended");
		}
		this.game = game;
		this.id = new GamePlayerPreferenceId(game.getId(), playerCount);
		this.isRecommended = isRecommended;
		this.isBest = isBest;
	}

	public Integer getPlayerCount() {
		return id.getPlayerCount();
	}
}
