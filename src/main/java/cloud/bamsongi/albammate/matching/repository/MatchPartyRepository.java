package cloud.bamsongi.albammate.matching.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.matching.entity.MatchParty;
import jakarta.persistence.LockModeType;

public interface MatchPartyRepository extends JpaRepository<MatchParty, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select party from MatchParty party where party.id = :partyId")
	Optional<MatchParty> findByIdForUpdate(@Param("partyId")
	Long partyId);
}
