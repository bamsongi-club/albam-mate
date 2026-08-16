package cloud.bamsongi.albammate.matching.entity;

import java.time.Instant;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import cloud.bamsongi.albammate.matching.MatchRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "match_requests")
public class MatchRequest extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "user_id", nullable = false)
	private Long userId;
	@Column(name = "game_id", nullable = false)
	private Long gameId;
	@Column(name = "min_party_size", nullable = false)
	private short minPartySize;
	@Column(name = "max_party_size", nullable = false)
	private short maxPartySize;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MatchRequestStatus status;
	@Column(name = "queued_at", nullable = false)
	private Instant queuedAt;
	@Column(name = "priority_since", nullable = false)
	private Instant prioritySince;
	@Column(name = "proposed_at")
	private Instant proposedAt;
	@Column(name = "matched_at")
	private Instant matchedAt;
	@Column(name = "purge_after")
	private Instant purgeAfter;

	public static MatchRequest create(
		long userId, long gameId, int minPartySize, int maxPartySize, MatchRequestStatus status) {
		MatchRequest request = new MatchRequest();
		Instant now = Instant.now();
		request.userId = userId;
		request.gameId = gameId;
		request.minPartySize = (short)minPartySize;
		request.maxPartySize = (short)maxPartySize;
		request.status = status;
		request.queuedAt = now;
		request.prioritySince = now;
		return request;
	}
}
