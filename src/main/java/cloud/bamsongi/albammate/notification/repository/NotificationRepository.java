package cloud.bamsongi.albammate.notification.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.notification.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	/** PostgreSQL 유일 제약을 멱등성 경계로 사용해 같은 수신자 저장을 성공으로 수렴시킨다. */
	@Modifying
	@Query(value = """
		insert into notifications (
		    source_event_id, recipient_user_id, room_id, type, read_at, created_at, recorded_at, expires_at
		) values (
		    :#{#notification.sourceEventId}, :#{#notification.recipientUserId}, :#{#notification.roomId},
		    :#{#notification.type.name()}, null, :#{#notification.createdAt}, :#{#notification.recordedAt},
		    :#{#notification.expiresAt}
		)
		on conflict (source_event_id, recipient_user_id) do nothing
		""", nativeQuery = true)
	int insertIfAbsent(@Param("notification")
	Notification notification);

	/** 고정된 PostgreSQL 기준 시각으로 만료 Notification을 인덱스 순서로 선점·삭제한다. */
	@Query(value = """
		with due_notifications as (
		    select notification.id
		    from notifications notification
		    where notification.expires_at <= :measurementTime
		    order by notification.expires_at asc, notification.id asc
		    limit :batchSize
		    for update of notification skip locked
		), deleted_notifications as (
		    delete from notifications notification
		    using due_notifications
		    where notification.id = due_notifications.id
		    returning notification.id
		)
		select count(*) from deleted_notifications
		""", nativeQuery = true)
	long deleteExpiredNotifications(@Param("measurementTime")
	Instant measurementTime, @Param("batchSize")
	int batchSize);
}
