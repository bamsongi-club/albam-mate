package cloud.bamsongi.albammate.matching.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.matching.entity.MatchProposalMember;
import cloud.bamsongi.albammate.matching.entity.MatchProposalMemberId;

public interface MatchProposalMemberRepository extends JpaRepository<MatchProposalMember, MatchProposalMemberId> {

	@Query("select member from MatchProposalMember member where member.id.proposalId = :proposalId order by member.id.matchRequestId")
	List<MatchProposalMember> findAllByProposalId(@Param("proposalId")
	long proposalId);

	@Query("""
		select member from MatchProposalMember member, MatchProposal proposal
		where member.id.proposalId = proposal.id
		  and member.id.matchRequestId = :matchRequestId
		  and proposal.status = 'OPEN'
		""")
	Optional<MatchProposalMember> findByMatchRequestId(@Param("matchRequestId")
	long matchRequestId);
}
