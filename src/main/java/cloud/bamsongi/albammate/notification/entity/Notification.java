package cloud.bamsongi.albammate.notification.entity;

import java.time.Instant;

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
}
