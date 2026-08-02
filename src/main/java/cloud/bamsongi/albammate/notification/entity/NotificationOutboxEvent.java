package cloud.bamsongi.albammate.notification.entity;

import java.time.Instant;

import cloud.bamsongi.albammate.notification.enums.NotificationOutboxEventType;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxStatus;
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
@Table(name = "notification_outbox_events")
public class NotificationOutboxEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 30)
	private NotificationOutboxEventType eventType;

	@Column(name = "room_id", nullable = false)
	private Long roomId;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "recorded_at", nullable = false)
	private Instant recordedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private NotificationOutboxStatus status;

	@Column(name = "available_at")
	private Instant availableAt;

	@Column(name = "failure_count", nullable = false)
	private int failureCount;

	@Column(name = "total_failure_count", nullable = false)
	private int totalFailureCount;

	@Column(name = "last_failure_code", length = 50)
	private String lastFailureCode;

	@Column(name = "last_failed_at")
	private Instant lastFailedAt;

	@Column(name = "last_failure_class", length = 255)
	private String lastFailureClass;

	@Column(name = "last_failure_message", length = 500)
	private String lastFailureMessage;

	@Column(name = "reprocess_count", nullable = false)
	private int reprocessCount;

	@Column(name = "last_reprocessed_at")
	private Instant lastReprocessedAt;

	@Column(name = "last_reprocess_reason", length = 500)
	private String lastReprocessReason;

	@Column(name = "processed_at")
	private Instant processedAt;

	@Column(name = "discarded_at")
	private Instant discardedAt;

	@Column(name = "discard_reason", length = 500)
	private String discardReason;

	@Column(name = "cleanup_at")
	private Instant cleanupAt;
}
