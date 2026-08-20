package cloud.bamsongi.albammate.matching.service.command;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.dto.MatchRequestCreateRequest;
import cloud.bamsongi.albammate.matching.service.query.MatchCurrentStateQueryCoordinator;

@Service
public class MatchRequestCommandService {

	private final MatchRequestCommandExecutor executor;
	private final MatchCurrentStateQueryCoordinator currentStateQueryCoordinator;

	public MatchRequestCommandService(
		MatchRequestCommandExecutor executor,
		MatchCurrentStateQueryCoordinator currentStateQueryCoordinator) {
		this.executor = executor;
		this.currentStateQueryCoordinator = currentStateQueryCoordinator;
	}

	public CreateResult create(long userId, String idempotencyKey, MatchRequestCreateRequest request) {
		CreateResult result = executor.create(userId, idempotencyKey, request);
		if (!result.replayed()) {
			return result;
		}
		return new CreateResult(currentStateQueryCoordinator.read(userId), true);
	}

	public CurrentMatchStateResponse cancel(long userId) {
		return executor.cancel(userId);
	}

	public record CreateResult(CurrentMatchStateResponse response, boolean replayed) {
	}
}
