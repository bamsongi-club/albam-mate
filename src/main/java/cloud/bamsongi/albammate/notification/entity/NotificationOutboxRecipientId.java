package cloud.bamsongi.albammate.notification.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Embeddable
public class NotificationOutboxRecipientId implements Serializable {

	private static final long serialVersionUID = 1L;

	@Column(name = "outbox_event_id", nullable = false)
	private Long outboxEventId;

	@Column(name = "recipient_user_id", nullable = false)
	private Long recipientUserId;

	private NotificationOutboxRecipientId(Long outboxEventId, Long recipientUserId) {
		this.outboxEventId = Objects.requireNonNull(outboxEventId, "outboxEventId");
		this.recipientUserId = Objects.requireNonNull(recipientUserId, "recipientUserId");
	}

	/** 확정된 Outbox 이벤트와 수신자를 복합 식별자로 묶는다. */
	public static NotificationOutboxRecipientId of(Long outboxEventId, Long recipientUserId) {
		return new NotificationOutboxRecipientId(outboxEventId, recipientUserId);
	}
}
