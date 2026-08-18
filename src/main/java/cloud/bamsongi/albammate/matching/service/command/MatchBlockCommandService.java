package cloud.bamsongi.albammate.matching.service.command;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.dto.MatchBlockListItemResponse;
import cloud.bamsongi.albammate.matching.entity.MatchBlock;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.repository.MatchBlockRepository;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import cloud.bamsongi.albammate.user.contract.UserPublicProfile;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import cloud.bamsongi.albammate.user.contract.UserRowLockPort;

/** 차단 관계의 생성과 해제를 같은 트랜잭션에서 처리한다. */
@Service
public class MatchBlockCommandService {

	private final MatchBlockRepository matchBlockRepository;
	private final MatchPartyRepository matchPartyRepository;
	private final MatchPartyParticipantRepository participantRepository;
	private final UserRowLockPort userRowLockPort;
	private final UserQuery userQuery;
	private final Clock clock;

	public MatchBlockCommandService(
		MatchBlockRepository matchBlockRepository,
		MatchPartyRepository matchPartyRepository,
		MatchPartyParticipantRepository participantRepository,
		UserRowLockPort userRowLockPort,
		UserQuery userQuery,
		Clock clock) {
		this.matchBlockRepository = Objects.requireNonNull(matchBlockRepository, "matchBlockRepository");
		this.matchPartyRepository = Objects.requireNonNull(matchPartyRepository, "matchPartyRepository");
		this.participantRepository = Objects.requireNonNull(participantRepository, "participantRepository");
		this.userRowLockPort = Objects.requireNonNull(userRowLockPort, "userRowLockPort");
		this.userQuery = Objects.requireNonNull(userQuery, "userQuery");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Transactional
	public MatchBlockListItemResponse block(long blockerUserId, long partyId, UUID participantRef) {
		verifyBlockerMembership(blockerUserId, partyId);
		matchPartyRepository.findById(partyId)
			.orElseThrow(() -> new BusinessException(ErrorCode.MATCH_PARTY_NOT_FOUND));
		MatchPartyParticipant blockedParticipant = participantRepository
			.findByPartyIdAndParticipantRef(partyId, participantRef)
			.orElseThrow(() -> new BusinessException(ErrorCode.MATCH_PARTICIPANT_NOT_FOUND));
		long blockedUserId = blockedParticipant.getId().getUserId();
		if (blockerUserId == blockedUserId) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}

		verifyBothUsersLocked(blockerUserId, blockedUserId);
		MatchBlock block = matchBlockRepository.findByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)
			.orElseGet(
				() -> matchBlockRepository.save(MatchBlock.create(blockerUserId, blockedUserId, Instant.now(clock))));
		UserPublicProfile blockedProfile = userQuery.findPublicProfileById(blockedUserId)
			.orElseThrow(() -> new IllegalStateException("locked blocked user must have a public profile"));
		return MatchBlockListItemResponse.from(block, blockedProfile);
	}

	@Transactional
	public void unblock(long blockerUserId, long blockId) {
		matchBlockRepository.deleteByIdAndBlockerUserId(blockId, blockerUserId);
	}

	private void verifyBlockerMembership(long blockerUserId, long partyId) {
		boolean isPartyMember = participantRepository.findParticipantByPartyIdAndUserId(partyId, blockerUserId)
			.isPresent();
		if (!isPartyMember) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}

	private void verifyBothUsersLocked(long blockerUserId, long blockedUserId) {
		var lockedUserIds = userRowLockPort.lockExistingUsersInAscendingOrder(List.of(blockerUserId, blockedUserId));
		if (!lockedUserIds.contains(blockerUserId) || !lockedUserIds.contains(blockedUserId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}
}
