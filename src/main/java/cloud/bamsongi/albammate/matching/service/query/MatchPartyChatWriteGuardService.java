package cloud.bamsongi.albammate.matching.service.query;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.contract.MatchPartyAccessQuery;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatAccess;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatWriteGuard;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MatchPartyChatWriteGuardService implements MatchPartyChatWriteGuard {

	private final MatchPartyRepository partyRepository;
	private final MatchPartyAccessQuery partyAccessQuery;

	@Override
	@Transactional
	public <T> T executeWithActiveAccess(long currentUserId, long partyId, Supplier<T> chatOperation) {
		Objects.requireNonNull(chatOperation, "chatOperation");
		partyRepository.findByIdForUpdate(partyId);
		MatchPartyChatAccess chatAccess = partyAccessQuery.evaluateChatAccess(currentUserId, partyId);
		requireAllowed(chatAccess);
		return chatOperation.get();
	}

	private void requireAllowed(MatchPartyChatAccess chatAccess) {
		if (chatAccess == MatchPartyChatAccess.NOT_ACTIVE) {
			throw new BusinessException(ErrorCode.MATCH_CHAT_NOT_ACTIVE);
		}
		if (chatAccess == MatchPartyChatAccess.FORBIDDEN) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}
}
