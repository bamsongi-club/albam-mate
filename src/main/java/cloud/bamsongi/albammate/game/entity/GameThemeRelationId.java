package cloud.bamsongi.albammate.game.entity;

import java.io.Serializable;

import jakarta.persistence.*;
import lombok.*;

@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class GameThemeRelationId implements Serializable {
	private static final long serialVersionUID = 1L;
	@Column(name = "game_id")
	private Long gameId;
	@Column(name = "theme_id")
	private Long themeId;
}
