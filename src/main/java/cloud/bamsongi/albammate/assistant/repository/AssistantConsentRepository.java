package cloud.bamsongi.albammate.assistant.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.assistant.entity.AssistantConsent;
import jakarta.persistence.LockModeType;

public interface AssistantConsentRepository extends JpaRepository<AssistantConsent, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select consent from AssistantConsent consent where consent.userId = :userId")
	Optional<AssistantConsent> findByUserIdForUpdate(@Param("userId")
	long userId);
}
