package cloud.bamsongi.albammate.notification.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Embeddable
public class NotificationOutboxRecipientId implements Serializable {

	private static final long serialVersionUID = 1L;

	@Column(name = "outbox_event_id", nullable = false)
	private Long outboxEventId;

	@Column(name = "recipient_user_id", nullable = false)
	private Long recipientUserId;
}
