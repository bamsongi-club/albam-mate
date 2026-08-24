package cloud.bamsongi.albammate.assistant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.assistant.entity.AssistantDraft;
import jakarta.persistence.LockModeType;

public interface AssistantDraftRepository extends JpaRepository<AssistantDraft, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select draft from AssistantDraft draft where draft.userId = :userId and draft.status = 'ACTIVE'")
	List<AssistantDraft> findActiveByUserIdForUpdate(@Param("userId")
	long userId);

	@Query("select draft from AssistantDraft draft where draft.userId = :userId and draft.status = 'ACTIVE'")
	Optional<AssistantDraft> findActiveByUserId(@Param("userId")
	long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select draft from AssistantDraft draft where draft.id = :id")
	Optional<AssistantDraft> findByIdForUpdate(@Param("id")
	long id);
}
