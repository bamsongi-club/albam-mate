package cloud.bamsongi.albammate.game.entity;

import jakarta.persistence.*;
import lombok.*;

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
		if (isBest && !isRecommended)
			throw new IllegalArgumentException("best requires recommended");
		this.game = game;
		this.id = new GamePlayerPreferenceId(game.getId(), playerCount);
		this.isRecommended = isRecommended;
		this.isBest = isBest;
	}

	public Integer getPlayerCount() {
		return id.getPlayerCount();
	}
}
