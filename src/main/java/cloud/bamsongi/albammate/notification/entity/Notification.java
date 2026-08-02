package cloud.bamsongi.albammate.notification.entity;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import cloud.bamsongi.albammate.notification.enums.NotificationType;
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
@Table(name = "notifications")
public class Notification {

	private static final Duration NOTIFICATION_RETENTION = Duration.ofDays(90);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "source_event_id", nullable = false)
	private Long sourceEventId;

	@Column(name = "recipient_user_id", nullable = false)
	private Long recipientUserId;

	@Column(name = "room_id", nullable = false)
	private Long roomId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 30)
	private NotificationType type;

	@Column(name = "read_at")
	private Instant readAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "recorded_at", nullable = false)
	private Instant recordedAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	/** relay가 수신자에게 아직 읽지 않은 Notification을 저장할 때 사용한다. */
	public static Notification createUnread(
		Long sourceEventId,
		Long recipientUserId,
		Long roomId,
		NotificationType type,
		Instant createdAt,
		Instant recordedAt) {
		Notification notification = new Notification();
		notification.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
		notification.recipientUserId = Objects.requireNonNull(recipientUserId, "recipientUserId");
		notification.roomId = Objects.requireNonNull(roomId, "roomId");
		notification.type = Objects.requireNonNull(type, "type");
		notification.readAt = null;
		notification.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		notification.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
		notification.expiresAt = createdAt.plus(NOTIFICATION_RETENTION);
		return notification;
	}
}
