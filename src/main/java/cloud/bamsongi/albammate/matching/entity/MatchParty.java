package cloud.bamsongi.albammate.matching.entity;

import java.time.Instant;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import cloud.bamsongi.albammate.matching.MatchPartyStatus;
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
@Table(name = "match_parties")
public class MatchParty extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "proposal_id")
	private Long proposalId;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MatchPartyStatus status;
	@Column(name = "preparing_started_at", nullable = false)
	private Instant preparingStartedAt;
	@Column(name = "chat_opened_at")
	private Instant chatOpenedAt;
	@Column(name = "closes_at")
	private Instant closesAt;
	@Column(name = "closed_at")
	private Instant closedAt;
	@Column(name = "purge_after")
	private Instant purgeAfter;
}
