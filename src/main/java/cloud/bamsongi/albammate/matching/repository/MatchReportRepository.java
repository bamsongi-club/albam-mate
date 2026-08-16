package cloud.bamsongi.albammate.matching.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import cloud.bamsongi.albammate.matching.entity.MatchReport;

public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {
}
