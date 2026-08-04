package cloud.bamsongi.albammate.game.entity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 사용자가 직접 표시한 현재 게임 관계다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_played_games", uniqueConstraints = {
	@UniqueConstraint(name = "uq_user_played_games_user_game", columnNames = {"user_id", "game_id"})
})
public class UserPlayedGame {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "game_id", nullable = false)
	private Long gameId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private UserPlayedGame(Long userId, Long gameId, Instant createdAt) {
		this.userId = Objects.requireNonNull(userId, "userId");
		this.gameId = Objects.requireNonNull(gameId, "gameId");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
	}

	/** 최초 표시 시각을 보존하는 새 관계를 만든다. */
	public static UserPlayedGame create(Long userId, Long gameId, Instant createdAt) {
		return new UserPlayedGame(userId, gameId, createdAt);
	}
}
