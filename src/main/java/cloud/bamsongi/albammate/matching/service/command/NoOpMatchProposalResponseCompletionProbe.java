package cloud.bamsongi.albammate.matching.service.command;

import java.time.Instant;

import org.springframework.stereotype.Component;

@Component
public class NoOpMatchProposalResponseCompletionProbe implements MatchProposalResponseCompletionProbe {

	@Override
	public void start(Instant operationTime) {}

	@Override
	public void complete() {}

	@Override
	public void fail(FailureStage failureStage) {}
}
