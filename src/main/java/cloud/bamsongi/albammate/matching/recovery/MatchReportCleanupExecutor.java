package cloud.bamsongi.albammate.matching.recovery;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.bamsongi.albammate.matching.entity.MatchReport;
import cloud.bamsongi.albammate.matching.repository.MatchReportRepository;
import cloud.bamsongi.albammate.user.contract.UserRowLockPort;

@Service
public class MatchReportCleanupExecutor {

	private final MatchReportRepository reportRepository;
	private final UserRowLockPort userRowLockPort;
	private final Clock clock;
	private final TransactionTemplate cleanupTransaction;

	public MatchReportCleanupExecutor(
		MatchReportRepository reportRepository,
		UserRowLockPort userRowLockPort,
		Clock clock,
		PlatformTransactionManager transactionManager) {
		this.reportRepository = reportRepository;
		this.userRowLockPort = userRowLockPort;
		this.clock = clock;
		this.cleanupTransaction = new TransactionTemplate(transactionManager);
		this.cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public int cleanupOneBatch(int batchSize) {
		Instant operationTime = Instant.now(clock);
		List<Long> expiredReportIds = reportRepository.findExpiredReportIds(operationTime, batchSize);
		int deletedCount = 0;
		for (Long reportId : expiredReportIds) {
			Boolean deleted = cleanupTransaction.execute(status -> deleteIfStillExpired(reportId, operationTime));
			if (Boolean.TRUE.equals(deleted)) {
				deletedCount++;
			}
		}
		return deletedCount;
	}

	private boolean deleteIfStillExpired(long reportId, Instant operationTime) {
		MatchReport report = reportRepository.findById(reportId).orElse(null);
		if (report == null || report.getPurgeAfter().isAfter(operationTime)) {
			return false;
		}
		Set<Long> lockedUserIds = userRowLockPort.lockExistingUsersInAscendingOrder(
			Set.of(report.getReporterUserId(), report.getReportedUserId()));
		if (lockedUserIds.size() != 2) {
			return false;
		}
		MatchReport lockedReport = reportRepository.findById(reportId).orElse(null);
		if (lockedReport == null || lockedReport.getPurgeAfter().isAfter(operationTime)) {
			return false;
		}
		reportRepository.delete(lockedReport);
		return true;
	}
}
