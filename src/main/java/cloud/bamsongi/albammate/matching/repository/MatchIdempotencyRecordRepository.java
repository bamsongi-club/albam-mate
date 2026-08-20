package cloud.bamsongi.albammate.matching.repository;

import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.matching.entity.MatchIdempotencyRecord;

public interface MatchIdempotencyRecordRepository extends JpaRepository<MatchIdempotencyRecord, Long> {

	@Query("""
		select record from MatchIdempotencyRecord record
		where record.userId = :userId and record.idempotencyKey = :idempotencyKey
		""")
	Optional<MatchIdempotencyRecord> findByUserIdAndIdempotencyKey(
		@Param("userId")
		long userId,
		@Param("idempotencyKey")
		String idempotencyKey);

	@Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select record from MatchIdempotencyRecord record
		where record.userId = :userId and record.idempotencyKey = :idempotencyKey
		""")
	Optional<MatchIdempotencyRecord> findByUserIdAndIdempotencyKeyForUpdate(
		@Param("userId")
		long userId, @Param("idempotencyKey")
		String idempotencyKey);

	@Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@Query("select record from MatchIdempotencyRecord record where record.id = :recordId")
	Optional<MatchIdempotencyRecord> findByIdForUpdate(@Param("recordId")
	long recordId);

	@Query("""
		select record.id as id, record.userId as userId
		from MatchIdempotencyRecord record
		where record.expiresAt <= :operationTime
		order by record.expiresAt asc, record.id asc
		""")
	java.util.List<ExpiredCandidate> findExpiredCandidateSnapshots(
		@Param("operationTime")
		java.time.Instant operationTime, Pageable pageable);

	interface ExpiredCandidate {

		Long getId();

		Long getUserId();
	}
}
