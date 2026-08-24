package cloud.bamsongi.albammate.matching.service.command;

import java.time.Instant;

public interface MatchProposalResponseCompletionProbe {

	void start(Instant operationTime);

	void complete();

	void fail(FailureStage failureStage);

	enum FailureStage {
		COMMAND_TRANSACTION,
		CURRENT_STATE_ASSEMBLY
	}
}
