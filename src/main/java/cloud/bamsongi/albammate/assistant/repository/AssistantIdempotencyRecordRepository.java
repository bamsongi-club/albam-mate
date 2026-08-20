package cloud.bamsongi.albammate.assistant.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.assistant.entity.AssistantIdempotencyRecord;
import jakarta.persistence.LockModeType;

public interface AssistantIdempotencyRecordRepository extends JpaRepository<AssistantIdempotencyRecord, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select record from AssistantIdempotencyRecord record where record.userId = :userId and record.draftId = :draftId and record.operation = 'DRAFT_CONFIRM'")
	Optional<AssistantIdempotencyRecord> findConfirmByUserAndDraftForUpdate(@Param("userId")
	long userId, @Param("draftId")
	long draftId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select record from AssistantIdempotencyRecord record where record.userId = :userId and record.keyHash = :keyHash")
	Optional<AssistantIdempotencyRecord> findByUserAndKeyHashForUpdate(@Param("userId")
	long userId, @Param("keyHash")
	String keyHash);

	void deleteByUserIdAndExpiresAtLessThanEqual(long userId, Instant now);
}
