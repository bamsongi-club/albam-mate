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

	private static final int MIN_PARTY_SIZE = 1;
	private static final int MAX_PARTY_SIZE = Short.MAX_VALUE;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "user_id", nullable = false)
	private Long userId;
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
		long userId, int minPartySize, int maxPartySize, MatchRequestStatus status) {
		validatePartySize(minPartySize, maxPartySize);

		MatchRequest request = new MatchRequest();
		Instant now = Instant.now();
		request.userId = userId;
		request.minPartySize = (short)minPartySize;
		request.maxPartySize = (short)maxPartySize;
		request.status = status;
		request.queuedAt = now;
		request.prioritySince = now;
		return request;
	}

	private static void validatePartySize(int minPartySize, int maxPartySize) {
		if (minPartySize < MIN_PARTY_SIZE
			|| maxPartySize > MAX_PARTY_SIZE
			|| minPartySize > maxPartySize) {
			throw new IllegalArgumentException(
				"매칭 인원 범위는 1 이상 32767 이하이며 최소값은 최대값보다 클 수 없습니다.");
		}
	}
}
