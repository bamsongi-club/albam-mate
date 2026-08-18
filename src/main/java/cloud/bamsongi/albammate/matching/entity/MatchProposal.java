package cloud.bamsongi.albammate.matching.entity;

import java.time.Instant;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import cloud.bamsongi.albammate.matching.MatchProposalStatus;
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
@Table(name = "match_proposals")
public class MatchProposal extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "party_size", nullable = false)
	private short partySize;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MatchProposalStatus status;
	@Column(name = "respond_by", nullable = false)
	private Instant respondBy;
	@Column(name = "confirmed_at")
	private Instant confirmedAt;
	@Column(name = "purge_after")
	private Instant purgeAfter;
}
