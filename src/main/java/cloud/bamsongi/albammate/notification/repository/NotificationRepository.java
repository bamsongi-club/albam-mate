package cloud.bamsongi.albammate.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.notification.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {}
