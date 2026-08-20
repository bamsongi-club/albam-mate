package cloud.bamsongi.albammate.matching.recovery;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.matching.entity.MatchIdempotencyRecord;
import cloud.bamsongi.albammate.matching.entity.MatchProposal;
import cloud.bamsongi.albammate.matching.entity.MatchRequest;
import cloud.bamsongi.albammate.matching.repository.MatchIdempotencyRecordRepository;
import cloud.bamsongi.albammate.matching.repository.MatchProposalRepository;
import cloud.bamsongi.albammate.matching.repository.MatchRequestRepository;
import cloud.bamsongi.albammate.user.contract.UserRowLockPort;

@Service
public class MatchRetentionCleanupExecutor {

	private static final int BATCH_SIZE = 100;

	private final MatchIdempotencyRecordRepository idempotencyRecordRepository;
	private final MatchRequestRepository requestRepository;
	private final MatchProposalRepository proposalRepository;
	private final UserRowLockPort userRowLockPort;
	private final JdbcTemplate jdbcTemplate;

	public MatchRetentionCleanupExecutor(
		MatchIdempotencyRecordRepository idempotencyRecordRepository,
		MatchRequestRepository requestRepository,
		MatchProposalRepository proposalRepository,
		UserRowLockPort userRowLockPort,
		JdbcTemplate jdbcTemplate) {
		this.idempotencyRecordRepository = idempotencyRecordRepository;
		this.requestRepository = requestRepository;
		this.proposalRepository = proposalRepository;
		this.userRowLockPort = userRowLockPort;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void cleanUpExpiredRecords() {
		Instant operationTime = jdbcTemplate.queryForObject("select current_timestamp", Timestamp.class).toInstant();
		cleanUpExpiredIdempotencyRecords(operationTime);
		cleanUpTerminalRequests(operationTime);
		cleanUpTerminalProposals(operationTime);
	}

	private void cleanUpExpiredIdempotencyRecords(Instant operationTime) {
		List<MatchIdempotencyRecord> candidates = idempotencyRecordRepository.findExpiredCandidates(
			operationTime, PageRequest.of(0, BATCH_SIZE));
		List<Long> userIds = candidates.stream()
			.map(MatchIdempotencyRecord::getUserId)
			.distinct()
			.sorted()
			.toList();
		if (!userIds.isEmpty()) {
			userRowLockPort.lockExistingUsersInAscendingOrder(userIds);
		}
		for (MatchIdempotencyRecord candidate : candidates) {
			idempotencyRecordRepository.findByIdForUpdate(candidate.getId())
				.filter(record -> record.isExpiredAt(operationTime))
				.ifPresent(idempotencyRecordRepository::delete);
		}
	}

	private void cleanUpTerminalRequests(Instant operationTime) {
		List<MatchRequest> requests = requestRepository.findTerminalPurgeCandidates(
			operationTime, PageRequest.of(0, BATCH_SIZE));
		requestRepository.deleteAll(requests);
	}

	private void cleanUpTerminalProposals(Instant operationTime) {
		List<MatchProposal> proposals = proposalRepository.findTerminalPurgeCandidates(
			operationTime, PageRequest.of(0, BATCH_SIZE));
		proposalRepository.deleteAll(proposals);
	}
}
