package cloud.bamsongi.albammate.matching.service.command;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.dto.MatchRequestCreateRequest;

@Service
public class MatchRequestCommandService {

	private final MatchRequestCommandExecutor executor;

	public MatchRequestCommandService(MatchRequestCommandExecutor executor) {
		this.executor = executor;
	}

	public CreateResult create(long userId, String idempotencyKey, MatchRequestCreateRequest request) {
		return executor.create(userId, idempotencyKey, request);
	}

	public CurrentMatchStateResponse cancel(long userId) {
		return executor.cancel(userId);
	}

	public record CreateResult(CurrentMatchStateResponse response, boolean replayed) {
	}
}
