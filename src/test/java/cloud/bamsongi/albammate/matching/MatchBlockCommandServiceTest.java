package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.repository.MatchBlockRepository;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import cloud.bamsongi.albammate.matching.service.command.MatchBlockCommandService;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import cloud.bamsongi.albammate.user.contract.UserRowLockPort;

@ExtendWith(MockitoExtension.class)
class MatchBlockCommandServiceTest {

	private static final long REQUESTER_USER_ID = 7L;
	private static final long PARTY_ID = 11L;
	private static final UUID PARTICIPANT_REF = UUID.randomUUID();

	@Mock
	private MatchBlockRepository matchBlockRepository;
	@Mock
	private MatchPartyRepository matchPartyRepository;
	@Mock
	private MatchPartyParticipantRepository participantRepository;
	@Mock
	private UserRowLockPort userRowLockPort;
	@Mock
	private UserQuery userQuery;

	@Test
	void 멤버십을_먼저_확인한_뒤_없는_Party를_구분하고_그_전에는_Party_존재를_조회하지_않는다() {
		when(participantRepository.findParticipantByPartyIdAndUserId(PARTY_ID, REQUESTER_USER_ID))
			.thenReturn(Optional.of(mock(MatchPartyParticipant.class)));
		when(matchPartyRepository.findById(PARTY_ID)).thenReturn(Optional.empty());

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> service().block(REQUESTER_USER_ID, PARTY_ID, PARTICIPANT_REF));

		assertEquals(ErrorCode.MATCH_PARTY_NOT_FOUND, exception.getErrorCode());
		InOrder order = inOrder(participantRepository, matchPartyRepository);
		order.verify(participantRepository).findParticipantByPartyIdAndUserId(PARTY_ID, REQUESTER_USER_ID);
		order.verify(matchPartyRepository).findById(PARTY_ID);
		order.verifyNoMoreInteractions();
		verifyNoInteractions(matchBlockRepository, userRowLockPort, userQuery);
	}

	private MatchBlockCommandService service() {
		return new MatchBlockCommandService(
			matchBlockRepository,
			matchPartyRepository,
			participantRepository,
			userRowLockPort,
			userQuery,
			Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC));
	}
}
