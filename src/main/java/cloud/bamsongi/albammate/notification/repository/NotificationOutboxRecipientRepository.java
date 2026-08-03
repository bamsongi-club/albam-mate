package cloud.bamsongi.albammate.notification.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.notification.entity.NotificationOutboxRecipient;
import cloud.bamsongi.albammate.notification.entity.NotificationOutboxRecipientId;

public interface NotificationOutboxRecipientRepository
	extends JpaRepository<NotificationOutboxRecipient, NotificationOutboxRecipientId> {

	/** relay가 원인 업무에서 고정한 수신자 스냅샷만 읽는다. */
	@Query("""
		select recipient.id.recipientUserId
		from NotificationOutboxRecipient recipient
		where recipient.id.outboxEventId = :outboxEventId
		order by recipient.id.recipientUserId asc
		""")
	List<Long> findRecipientUserIdsByOutboxEventId(@Param("outboxEventId")
	Long outboxEventId);

	boolean existsByIdOutboxEventId(Long outboxEventId);

	@Modifying
	long deleteByIdOutboxEventIdIn(List<Long> outboxEventIds);
}
