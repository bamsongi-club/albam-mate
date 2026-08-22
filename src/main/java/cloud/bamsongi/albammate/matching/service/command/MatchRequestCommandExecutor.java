package cloud.bamsongi.albammate.matching.service.command;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.MatchIdempotencyOperation;
import cloud.bamsongi.albammate.matching.MatchRequestStatus;
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.dto.MatchRequestCreateRequest;
import cloud.bamsongi.albammate.matching.entity.MatchIdempotencyRecord;
import cloud.bamsongi.albammate.matching.entity.MatchRequest;
import cloud.bamsongi.albammate.matching.repository.MatchIdempotencyRecordRepository;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import cloud.bamsongi.albammate.matching.repository.MatchRequestRepository;
import cloud.bamsongi.albammate.user.contract.UserRowLockPort;

@Service
public class MatchRequestCommandExecutor {

	private final MatchRequestRepository requestRepository;
	private final MatchIdempotencyRecordRepository idempotencyRecordRepository;
	private final MatchPartyParticipantRepository participantRepository;
	private final UserRowLockPort userRowLockPort;
	private final MatchProposalResponseExecutor proposalResponseExecutor;
	private final JdbcTemplate jdbcTemplate;

	public MatchRequestCommandExecutor(
		MatchRequestRepository requestRepository,
		MatchIdempotencyRecordRepository idempotencyRecordRepository,
		MatchPartyParticipantRepository participantRepository,
		UserRowLockPort userRowLockPort,
		MatchProposalResponseExecutor proposalResponseExecutor,
		JdbcTemplate jdbcTemplate) {
		this.requestRepository = requestRepository;
		this.idempotencyRecordRepository = idempotencyRecordRepository;
		this.participantRepository = participantRepository;
		this.userRowLockPort = userRowLockPort;
		this.proposalResponseExecutor = proposalResponseExecutor;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional
	public MatchRequestCommandService.CreateResult create(
		long userId, String idempotencyKey, MatchRequestCreateRequest command) {
		if (!command.hasValidRange()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		userRowLockPort.lockExistingUsersInAscendingOrder(List.of(userId));
		Instant operationTime = currentDatabaseTime();
		String fingerprint = command.minPlayers() + ":" + command.maxPlayers();
		MatchIdempotencyRecord record = idempotencyRecordRepository
			.findByUserIdAndIdempotencyKeyForUpdate(userId, idempotencyKey)
			.orElse(null);
		if (record != null && !record.isExpiredAt(operationTime)) {
			if (!record.hasSameMeaning(MatchIdempotencyOperation.MATCH_REQUEST_CREATE, fingerprint)) {
				throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
			}
			return new MatchRequestCommandService.CreateResult(null, true);
		}
		MatchRequest currentRequest = requestRepository.findCurrentByUserId(userId).orElse(null);
		if (currentRequest != null && currentRequest.getStatus() != MatchRequestStatus.PAUSED) {
			throw new BusinessException(ErrorCode.MATCH_REQUEST_ALREADY_ACTIVE);
		}
		if (participantRepository.existsCurrentPreparingOrActivePartyByUserId(userId)) {
			throw new BusinessException(ErrorCode.MATCH_REQUEST_ALREADY_ACTIVE);
		}
		MatchRequest request;
		if (currentRequest == null) {
			request = requestRepository.save(MatchRequest.create(
				userId, command.minPlayers(), command.maxPlayers(), MatchRequestStatus.WAITING, operationTime));
		} else {
			currentRequest.startNewWaitingAttempt(operationTime);
			request = currentRequest;
		}
		if (record == null) {
			idempotencyRecordRepository.save(MatchIdempotencyRecord.create(
				userId, idempotencyKey, MatchIdempotencyOperation.MATCH_REQUEST_CREATE,
				fingerprint, "MATCH_REQUEST", request.getId(), MatchRequestStatus.WAITING.name(), operationTime));
		} else {
			record.replace(MatchIdempotencyOperation.MATCH_REQUEST_CREATE, fingerprint, "MATCH_REQUEST",
				request.getId(),
				MatchRequestStatus.WAITING.name(), operationTime);
		}
		return new MatchRequestCommandService.CreateResult(
			CurrentMatchStateResponse.waiting(operationTime, request), false);
	}

	public CurrentMatchStateResponse cancel(long userId) {
		Instant operationTime = currentDatabaseTime();
		MatchRequest request = requestRepository.findCurrentByUserId(userId).orElse(null);
		if (request != null && request.getStatus() == MatchRequestStatus.PROPOSED
			&& proposalResponseExecutor.cancelOpenProposalForRequest(userId, request.getId())) {
			return CurrentMatchStateResponse.empty(operationTime);
		}
		if (participantRepository.existsCurrentPreparingOrActivePartyByUserId(userId)) {
			throw new BusinessException(ErrorCode.MATCH_REQUEST_CANCELLATION_NOT_AVAILABLE);
		}
		if (request == null) {
			return CurrentMatchStateResponse.empty(operationTime);
		}
		int canceledRequestCount = jdbcTemplate.update("""
			update match_requests
			set status = 'CANCELED', purge_after = ?
			where id = ?
			  and status in ('WAITING', 'PAUSED')
			""", Timestamp.from(MatchRequest.terminalPurgeAfter(operationTime)), request.getId());
		if (canceledRequestCount == 1) {
			return CurrentMatchStateResponse.empty(operationTime);
		}
		MatchRequest latestRequest = requestRepository.findCurrentByUserId(userId).orElse(null);
		if (latestRequest != null && latestRequest.getStatus() == MatchRequestStatus.PROPOSED
			&& proposalResponseExecutor.cancelOpenProposalForRequest(userId, latestRequest.getId())) {
			return CurrentMatchStateResponse.empty(operationTime);
		}
		return CurrentMatchStateResponse.empty(operationTime);
	}

	private Instant currentDatabaseTime() {
		return jdbcTemplate.queryForObject("select current_timestamp", Timestamp.class).toInstant();
	}
}
