package cloud.bamsongi.albammate.matching.entity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "match_blocks")
public class MatchBlock {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "blocker_user_id", nullable = false)
	private Long blockerUserId;
	@Column(name = "blocked_user_id", nullable = false)
	private Long blockedUserId;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public static MatchBlock create(long blockerUserId, long blockedUserId, Instant createdAt) {
		if (blockerUserId == blockedUserId) {
			throw new IllegalArgumentException("blockerUserId and blockedUserId must differ");
		}
		MatchBlock matchBlock = new MatchBlock();
		matchBlock.blockerUserId = blockerUserId;
		matchBlock.blockedUserId = blockedUserId;
		matchBlock.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		return matchBlock;
	}
}
