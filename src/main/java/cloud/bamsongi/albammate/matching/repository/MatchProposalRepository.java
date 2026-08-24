package cloud.bamsongi.albammate.matching.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.matching.entity.MatchProposal;

public interface MatchProposalRepository extends JpaRepository<MatchProposal, Long> {

	@Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@Query("select proposal from MatchProposal proposal where proposal.id = :proposalId")
	Optional<MatchProposal> findByIdForUpdate(@Param("proposalId")
	long proposalId);

	@Query("""
		select proposal.id from MatchProposal proposal
		where proposal.status = 'OPEN'
		  and proposal.respondBy <= current_timestamp
		order by proposal.respondBy asc, proposal.id asc
		""")
	List<Long> findDueOpenIds(Pageable pageable);

	@Query("""
		select proposal from MatchProposal proposal
		where proposal.status in ('CONFIRMED', 'DECLINED', 'EXPIRED', 'CANCELED')
		  and proposal.purgeAfter <= :operationTime
		order by proposal.purgeAfter asc, proposal.id asc
		""")
	List<MatchProposal> findTerminalPurgeCandidates(
		@Param("operationTime")
		Instant operationTime, Pageable pageable);
}
