package cloud.bamsongi.albammate.matching.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.matching.entity.MatchIdempotencyRecord;

public interface MatchIdempotencyRecordRepository extends JpaRepository<MatchIdempotencyRecord, Long> {}
