package cloud.bamsongi.albammate.matching.service.command;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.matching.MatchReportReason;
import cloud.bamsongi.albammate.matching.dto.MatchReportReceiptResponse;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MatchReportCommandService {

	private final MatchReportCommandExecutor executor;

	@Transactional
	public MatchReportReceiptResponse report(
		long reporterUserId,
		long partyId,
		UUID participantRef,
		MatchReportReason reason) {
		return executor.createOrReuse(reporterUserId, partyId, participantRef, reason);
	}
}
