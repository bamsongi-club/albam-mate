package cloud.bamsongi.albammate.matching.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.matching.entity.MatchRequest;

public interface MatchRequestRepository extends JpaRepository<MatchRequest, Long> {}
