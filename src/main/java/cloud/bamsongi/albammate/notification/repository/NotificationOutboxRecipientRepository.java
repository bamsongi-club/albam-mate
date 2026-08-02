package cloud.bamsongi.albammate.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.notification.entity.NotificationOutboxRecipient;
import cloud.bamsongi.albammate.notification.entity.NotificationOutboxRecipientId;

public interface NotificationOutboxRecipientRepository
	extends JpaRepository<NotificationOutboxRecipient, NotificationOutboxRecipientId> {}
