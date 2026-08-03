package cloud.bamsongi.albammate.notification.repository;

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
}
