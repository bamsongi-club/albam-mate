package cloud.bamsongi.albammate.notification.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notification_outbox_recipients")
public class NotificationOutboxRecipient {

	@EmbeddedId
	private NotificationOutboxRecipientId id;

	/** 원인 업무가 확정한 수신자 스냅샷 행을 만든다. */
	public static NotificationOutboxRecipient create(Long outboxEventId, Long recipientUserId) {
		NotificationOutboxRecipient recipient = new NotificationOutboxRecipient();
		recipient.id = NotificationOutboxRecipientId.of(outboxEventId, recipientUserId);
		return recipient;
	}
}
