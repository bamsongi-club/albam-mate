package cloud.bamsongi.albammate.notification.repository;

import java.util.Collection;
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

	/** 요청한 이벤트 중 수신자 스냅샷이 하나 이상 존재하는 이벤트 ID만 중복 없이 반환한다. */
	@Query("""
		select distinct recipient.id.outboxEventId
		from NotificationOutboxRecipient recipient
		where recipient.id.outboxEventId in :outboxEventIds
		""")
	List<Long> findOutboxEventIdsWithRecipients(@Param("outboxEventIds")
	Collection<Long> outboxEventIds);

	@Modifying
	long deleteByIdOutboxEventIdIn(List<Long> outboxEventIds);
}
