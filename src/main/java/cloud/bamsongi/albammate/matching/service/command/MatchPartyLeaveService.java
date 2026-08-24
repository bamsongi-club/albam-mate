package cloud.bamsongi.albammate.matching.service.command;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;

@Service
public class MatchPartyLeaveService {

	private final MatchPartyLeaveExecutor executor;

	public MatchPartyLeaveService(MatchPartyLeaveExecutor executor) {
		this.executor = executor;
	}

	public CurrentMatchStateResponse leave(long partyId, long userId) {
		return executor.leave(partyId, userId);
	}
}
