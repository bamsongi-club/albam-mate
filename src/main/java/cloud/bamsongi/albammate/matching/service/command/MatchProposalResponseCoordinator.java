package cloud.bamsongi.albammate.matching.service.command;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.matching.MatchProposalResponseAction;
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.service.query.MatchCurrentStateQueryCoordinator;

@Service
public class MatchProposalResponseCoordinator {

	private final MatchProposalResponseService responseService;
	private final MatchCurrentStateQueryCoordinator currentStateQueryCoordinator;
	private final MatchProposalResponseCompletionProbe completionProbe;

	public MatchProposalResponseCoordinator(
		MatchProposalResponseService responseService,
		MatchCurrentStateQueryCoordinator currentStateQueryCoordinator,
		MatchProposalResponseCompletionProbe completionProbe) {
		this.responseService = responseService;
		this.currentStateQueryCoordinator = currentStateQueryCoordinator;
		this.completionProbe = completionProbe;
	}

	public CurrentMatchStateResponse respond(
		long userId,
		long proposalId,
		MatchProposalResponseAction action,
		String idempotencyKey) {
		boolean responseAttemptStarted = responseService.respond(userId, proposalId, action, idempotencyKey);
		if (!responseAttemptStarted) {
			return currentStateQueryCoordinator.read(userId);
		}
		try {
			CurrentMatchStateResponse currentState = currentStateQueryCoordinator.read(userId);
			safelyCompleteProbe();
			return currentState;
		} catch (RuntimeException exception) {
			safelyFailProbe(MatchProposalResponseCompletionProbe.FailureStage.CURRENT_STATE_ASSEMBLY);
			throw exception;
		}
	}

	private void safelyCompleteProbe() {
		try {
			completionProbe.complete();
		} catch (RuntimeException ignored) {
			// 측정 기록 실패는 이미 확정된 응답 결과를 바꾸지 않는다.
		}
	}

	private void safelyFailProbe(MatchProposalResponseCompletionProbe.FailureStage failureStage) {
		try {
			completionProbe.fail(failureStage);
		} catch (RuntimeException ignored) {
			// 측정 기록 실패는 원래 업무 예외를 덮어쓰지 않는다.
		}
	}
}
