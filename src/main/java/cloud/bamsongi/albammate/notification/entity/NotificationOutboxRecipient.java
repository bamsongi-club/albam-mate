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
}
