package cloud.bamsongi.albammate.matching.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.matching.entity.MatchProposalMember;
import cloud.bamsongi.albammate.matching.entity.MatchProposalMemberId;

public interface MatchProposalMemberRepository extends JpaRepository<MatchProposalMember, MatchProposalMemberId> {}
