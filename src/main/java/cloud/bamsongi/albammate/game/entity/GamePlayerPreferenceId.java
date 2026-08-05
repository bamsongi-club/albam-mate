package cloud.bamsongi.albammate.game.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class GamePlayerPreferenceId implements Serializable {
	private static final long serialVersionUID = 1L;
	@Column(name = "game_id")
	private Long gameId;
	@Column(name = "player_count")
	private Integer playerCount;

	public Integer getPlayerCount() {
		return playerCount;
	}
}
