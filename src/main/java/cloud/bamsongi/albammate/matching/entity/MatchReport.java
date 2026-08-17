package cloud.bamsongi.albammate.matching.entity;

import java.time.Instant;

import cloud.bamsongi.albammate.matching.MatchReportReason;
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
@Table(name = "match_reports")
public class MatchReport {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "reporter_user_id", nullable = false)
	private Long reporterUserId;
	@Column(name = "reported_user_id", nullable = false)
	private Long reportedUserId;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private MatchReportReason reason;
	@Column(name = "reported_at", nullable = false)
	private Instant reportedAt;
	@Column(name = "purge_after", nullable = false)
	private Instant purgeAfter;
}
