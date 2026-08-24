package cloud.bamsongi.albammate.matching.recovery;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MatchReportCleanupCoordinator {

	private static final int MAX_BATCHES_PER_RUN = 10;
	private static final int BATCH_SIZE = 100;

	private final MatchReportCleanupExecutor executor;

	public void purgeExpiredReports() {
		for (int batchNumber = 0; batchNumber < MAX_BATCHES_PER_RUN; batchNumber++) {
			int deletedCount = executor.cleanupOneBatch(BATCH_SIZE);
			if (deletedCount < BATCH_SIZE) {
				return;
			}
		}
	}
}
