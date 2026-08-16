package cloud.bamsongi.albammate.matching.service.query;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.MatchPartyStatus;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatWriteGuard;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MatchPartyChatWriteGuardService implements MatchPartyChatWriteGuard {

	private final MatchPartyRepository partyRepository;
	private final MatchPartyParticipantRepository participantRepository;

	@Override
	@Transactional
	public <T> T executeWithActiveAccess(long currentUserId, long partyId, Supplier<T> chatOperation) {
		Objects.requireNonNull(chatOperation, "chatOperation");
		boolean hasCurrentAccess = partyRepository.findByIdForUpdate(partyId)
			.filter(party -> party.getStatus() == MatchPartyStatus.ACTIVE)
			.flatMap(party -> participantRepository.findCurrentByPartyIdAndUserId(partyId, currentUserId))
			.isPresent();
		if (!hasCurrentAccess) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		return chatOperation.get();
	}
}
