package cloud.bamsongi.albammate.notification.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	/** 실패 기록 트랜잭션의 PostgreSQL 시각으로 아직 처리 가능한 동일 이벤트만 갱신한다. */
	@Query(value = """
			with operation as materialized (
			    select clock_timestamp() as operation_time
			), locked_event as (
			    select event.id, event.occurred_at, event.failure_count, event.total_failure_count,
			        operation.operation_time
			    from notification_outbox_events event
			    cross join operation
			    where event.id = :eventId
			      and event.status in ('PENDING', 'RETRY_WAIT')
			    for update of event
			), transition as (
			    select locked_event.*,
			        locked_event.failure_count + 1 as next_failure_count,
			        locked_event.total_failure_count + 1 as next_total_failure_count,
			        case
			            when :deterministicFailure
			                or locked_event.operation_time >= locked_event.occurred_at + interval '90 days'
			                or locked_event.failure_count + 1 >= :maxAutomaticAttempts then 'FAILED'
			            else 'RETRY_WAIT'
			        end as next_status
			    from locked_event
			), updated as (
			    update notification_outbox_events event
			    set status = transition.next_status,
			        available_at = case
			            when transition.next_status = 'RETRY_WAIT' then transition.operation_time + (
			                case transition.next_failure_count
			                    when 1 then :firstRetryDelaySeconds
			                    when 2 then :secondRetryDelaySeconds
			                    when 3 then :thirdRetryDelaySeconds
			                    else :fourthRetryDelaySeconds
			                end * interval '1 second')
			            else null
			        end,
			        failure_count = transition.next_failure_count,
			        total_failure_count = transition.next_total_failure_count,
			        last_failure_code = case
			            when transition.operation_time >= transition.occurred_at + interval '90 days'
			                then 'NOTIFICATION_EXPIRED'
			            else :failureCode
			        end,
			        last_failed_at = transition.operation_time,
			        last_failure_class = case
			            when transition.operation_time >= transition.occurred_at + interval '90 days'
			                then 'NotificationExpired'
			            else :failureClass
			        end,
			        last_failure_message = case
			            when transition.operation_time >= transition.occurred_at + interval '90 days'
			                then 'Notification event expired before relay processing'
			            else :failureMessage
			        end
			    from transition
			    where event.id = transition.id
			    returning event.id, event.event_type, event.status, event.available_at, event.failure_count,
			        event.total_failure_count, event.last_failure_code, event.last_failure_class
			)
		select updated.id as "sourceEventId", updated.event_type as "eventType", updated.status as "status",
		    updated.available_at as "nextAvailableAt", updated.failure_count as "failureCount",
		    updated.total_failure_count as "totalFailureCount", updated.last_failure_code as "failureCode",
		    updated.last_failure_class as "failureClass", (:deterministicFailure
		        or exists (select 1 from transition where transition.operation_time >= transition.occurred_at + interval '90 days'))
		        as "deterministicFailure",
			    (updated.status = 'RETRY_WAIT') as "retryScheduled"
			from updated
			""", nativeQuery = true)
	Optional<RelayFailureRecord> recordRelayFailure(
		@Param("eventId")
		long eventId,
		@Param("failureCode")
		String failureCode,
		@Param("failureClass")
		String failureClass,
		@Param("failureMessage")
		String failureMessage,
		@Param("deterministicFailure")
		boolean deterministicFailure,
		@Param("maxAutomaticAttempts")
		int maxAutomaticAttempts,
		@Param("firstRetryDelaySeconds")
		long firstRetryDelaySeconds,
		@Param("secondRetryDelaySeconds")
		long secondRetryDelaySeconds,
		@Param("thirdRetryDelaySeconds")
		long thirdRetryDelaySeconds,
		@Param("fourthRetryDelaySeconds")
		long fourthRetryDelaySeconds);

	interface RelayClaim {

		Long getId();

		Instant getAvailableAt();

		Instant getOperationTime();
	}

	interface RelayFailureRecord {

		long getSourceEventId();

		String getEventType();

		Instant getNextAvailableAt();

		int getFailureCount();

		int getTotalFailureCount();

		String getFailureCode();

		String getFailureClass();

		boolean isDeterministicFailure();

		boolean isRetryScheduled();
	}
}
