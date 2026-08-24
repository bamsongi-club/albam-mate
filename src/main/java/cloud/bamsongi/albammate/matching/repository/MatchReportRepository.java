package cloud.bamsongi.albammate.matching.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.matching.entity.MatchReport;

public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {

	Optional<MatchReport> findByReporterUserIdAndReportedUserId(long reporterUserId, long reportedUserId);

	@Query("""
		select report.id
		from MatchReport report
		where report.purgeAfter <= :operationTime
		order by report.purgeAfter asc, report.id asc
		limit :batchSize
		""")
	List<Long> findExpiredReportIds(
		@Param("operationTime")
		Instant operationTime,
		@Param("batchSize")
		int batchSize);
}
