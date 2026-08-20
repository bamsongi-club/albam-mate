package cloud.bamsongi.albammate.matching.repository;

import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.matching.entity.MatchRequest;

public interface MatchRequestRepository extends JpaRepository<MatchRequest, Long> {

	@Query("""
		select request from MatchRequest request
		where request.userId = :userId
		  and request.status in ('WAITING', 'PROPOSED', 'PAUSED')
		order by request.id desc
		""")
	Optional<MatchRequest> findCurrentByUserId(@Param("userId")
	long userId);

	@org.springframework.data.jpa.repository.Query(value = """
		select * from match_requests
		where status = 'WAITING'
		order by priority_since asc, id asc
		limit 1
		for update skip locked
		""", nativeQuery = true)
	java.util.List<MatchRequest> findOldestWaitingForUpdateSkipLocked();

	@org.springframework.data.jpa.repository.Query(value = """
		select * from match_requests
		where status = 'WAITING'
		  and (priority_since > :afterPrioritySince
			or (priority_since = :afterPrioritySince and id > :afterRequestId))
		order by priority_since asc, id asc
		limit :candidatePageSize
		for update skip locked
		""", nativeQuery = true)
	java.util.List<MatchRequest> findWaitingAfterForUpdateSkipLocked(
		@Param("afterPrioritySince")
		java.time.Instant afterPrioritySince,
		@Param("afterRequestId")
		long afterRequestId,
		@Param("candidatePageSize")
		int candidatePageSize);

	@Query("""
		select request from MatchRequest request
		where request.status in ('MATCHED', 'CANCELED')
		  and request.purgeAfter <= :operationTime
		order by request.purgeAfter asc, request.id asc
		""")
	java.util.List<MatchRequest> findTerminalPurgeCandidates(
		@Param("operationTime")
		java.time.Instant operationTime, Pageable pageable);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update MatchRequest request
		set request.status = 'CANCELED', request.purgeAfter = :purgeAfter
		where request.id = :requestId
		  and request.status in ('WAITING', 'PAUSED')
		""")
	int cancelWaitingOrPaused(
		@Param("requestId")
		long requestId, @Param("purgeAfter")
		java.time.Instant purgeAfter);
}
