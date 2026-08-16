package cloud.bamsongi.albammate.matching.entity;

import java.time.Instant;

import cloud.bamsongi.albammate.matching.MatchIdempotencyOperation;
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
@Table(name = "match_idempotency_records")
public class MatchIdempotencyRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "user_id", nullable = false)
	private Long userId;
	@Column(name = "idempotency_key", nullable = false, length = 100)
	private String idempotencyKey;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private MatchIdempotencyOperation operation;
	@Column(name = "payload_fingerprint", nullable = false)
	private String payloadFingerprint;
	@Column(name = "result_entity_type")
	private String resultEntityType;
	@Column(name = "result_entity_id")
	private Long resultEntityId;
	@Column(name = "result_state")
	private String resultState;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;
}
