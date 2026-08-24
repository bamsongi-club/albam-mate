package cloud.bamsongi.albammate.matching.service.command;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.MatchReportReason;
import cloud.bamsongi.albammate.matching.dto.MatchReportReceiptResponse;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.entity.MatchReport;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import cloud.bamsongi.albammate.matching.repository.MatchReportRepository;
import cloud.bamsongi.albammate.user.contract.UserRowLockPort;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MatchReportCommandExecutor {

	private static final long RETENTION_DAYS = 7;

	private final MatchPartyParticipantRepository participantRepository;
	private final MatchPartyRepository partyRepository;
	private final MatchReportRepository reportRepository;
	private final UserRowLockPort userRowLockPort;
	private final Clock clock;

	public MatchReportReceiptResponse createOrReuse(
		long reporterUserId,
		long partyId,
		UUID participantRef,
		MatchReportReason reason) {
		MatchPartyParticipant reporterParticipant = participantRepository
			.findParticipantByPartyIdAndUserId(partyId, reporterUserId)
			.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
		if (!partyRepository.existsById(partyId)) {
			throw new BusinessException(ErrorCode.MATCH_PARTY_NOT_FOUND);
		}
		MatchPartyParticipant reportedParticipant = participantRepository
			.findByPartyIdAndParticipantRef(partyId, participantRef)
			.orElseThrow(() -> new BusinessException(ErrorCode.MATCH_PARTICIPANT_NOT_FOUND));
		long reportedUserId = reportedParticipant.getId().getUserId();
		if (reporterParticipant.getId().getUserId().equals(reportedUserId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}

		Set<Long> lockedUserIds = userRowLockPort.lockExistingUsersInAscendingOrder(
			Set.of(reporterUserId, reportedUserId));
		if (lockedUserIds.size() != 2) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}

		Instant operationTime = Instant.now(clock);
		Instant purgeAfter = operationTime.plus(RETENTION_DAYS, ChronoUnit.DAYS);
		return reportRepository.findByReporterUserIdAndReportedUserId(reporterUserId, reportedUserId)
			.map(report -> receiptForExistingReport(report, reason, operationTime, purgeAfter))
			.orElseGet(() -> createNewReceipt(reporterUserId, reportedUserId, reason, operationTime, purgeAfter));
	}

	private MatchReportReceiptResponse receiptForExistingReport(
		MatchReport report,
		MatchReportReason reason,
		Instant operationTime,
		Instant purgeAfter) {
		if (report.getPurgeAfter().isAfter(operationTime)) {
			return new MatchReportReceiptResponse(report.getReportedAt(), true);
		}
		report.replaceExpiredReport(reason, operationTime, purgeAfter);
		return new MatchReportReceiptResponse(operationTime, false);
	}

	private MatchReportReceiptResponse createNewReceipt(
		long reporterUserId,
		long reportedUserId,
		MatchReportReason reason,
		Instant operationTime,
		Instant purgeAfter) {
		MatchReport report = MatchReport.create(reporterUserId, reportedUserId, reason, operationTime, purgeAfter);
		reportRepository.save(report);
		return new MatchReportReceiptResponse(operationTime, false);
	}
}
