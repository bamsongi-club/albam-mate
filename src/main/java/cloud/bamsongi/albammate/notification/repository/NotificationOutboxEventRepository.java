package cloud.bamsongi.albammate.notification.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;

public interface NotificationOutboxEventRepository extends JpaRepository<NotificationOutboxEvent, Long> {

	/** PostgreSQL 기준 시각으로 가장 이른 처리 가능 이벤트 하나를 잠근다. */
	@Query(value = """
		with operation as materialized (
		    select clock_timestamp() as operation_time
		), claimable as (
		    select event.id as id, event.available_at as available_at, operation.operation_time as operation_time
		    from notification_outbox_events event
		    cross join operation
		    where event.status in ('PENDING', 'RETRY_WAIT')
		      and event.available_at <= operation.operation_time
		    order by event.available_at asc, event.id asc
		    limit 1
		    for update of event skip locked
		)
		select id as id, available_at as "availableAt", operation_time as "operationTime"
		from claimable
		""", nativeQuery = true)
	Optional<RelayClaim> claimEarliestProcessableEvent();

	/** 아직 처리 가능한 이벤트 중 가장 오래 기다린 시간만 batch 로그에 제공한다. */
	@Query(value = """
		with operation as materialized (
		    select clock_timestamp() as operation_time
		)
		select extract(epoch from (
		    operation.operation_time - (
		        select min(event.available_at)
		        from notification_outbox_events event
		        where event.status in ('PENDING', 'RETRY_WAIT')
		          and event.available_at <= operation.operation_time
		    )
		)) * 1000
		from operation
		""", nativeQuery = true)
	Long findOldestProcessableAgeMillis();

	interface RelayClaim {

		Long getId();

		Instant getAvailableAt();

		Instant getOperationTime();
	}
}
