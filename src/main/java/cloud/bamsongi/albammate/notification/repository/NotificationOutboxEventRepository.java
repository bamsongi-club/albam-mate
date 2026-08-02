package cloud.bamsongi.albammate.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;

public interface NotificationOutboxEventRepository extends JpaRepository<NotificationOutboxEvent, Long> {}
