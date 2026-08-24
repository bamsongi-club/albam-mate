package cloud.bamsongi.albammate.matching.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.matching.MatchPartyStatus;
import cloud.bamsongi.albammate.matching.contract.MatchChatSystemMessagePort;
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.dto.MatchPreparingMember;
import cloud.bamsongi.albammate.matching.entity.MatchParty;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipantId;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import cloud.bamsongi.albammate.matching.repository.MatchProposalMemberRepository;
import cloud.bamsongi.albammate.matching.repository.MatchProposalRepository;
import cloud.bamsongi.albammate.matching.repository.MatchRequestRepository;
import cloud.bamsongi.albammate.user.contract.UserPublicProfile;
import cloud.bamsongi.albammate.user.contract.UserQuery;

class MatchCurrentStateReadServiceTest {

	private static final Instant OPERATION_TIME = Instant.parse("2024-01-01T00:00:00Z");

	@Test
	void PREPARING_상태는_확정된_파티_참가자의_닉네임_프로필이미지_isMine을_members로_반환한다() {
		long currentUserId = 1L;
		long otherUserId = 2L;
		MatchParty party = mock(MatchParty.class);
		when(party.getStatus()).thenReturn(MatchPartyStatus.PREPARING);
		when(party.getId()).thenReturn(10L);
		when(party.getPreparingStartedAt()).thenReturn(OPERATION_TIME.minusSeconds(10));

		MatchPartyParticipant currentParticipant = participant(10L, currentUserId);
		MatchPartyParticipant otherParticipant = participant(10L, otherUserId);

		MatchPartyParticipantRepository participantRepository = mock(MatchPartyParticipantRepository.class);
		when(participantRepository.findAllByIdPartyIdAndLeftAtIsNullOrderByCreatedAtAsc(10L))
			.thenReturn(List.of(currentParticipant, otherParticipant));

		UserQuery userQuery = mock(UserQuery.class);
		when(userQuery.findPublicProfilesByIds(List.of(currentUserId, otherUserId))).thenReturn(Map.of(
			currentUserId, new UserPublicProfile(currentUserId, "나", "https://example.com/me.png"),
			otherUserId, new UserPublicProfile(otherUserId, "상대", null)));

		MatchCurrentStateReadService service = service(participantRepository, userQuery);

		CurrentMatchStateResponse response = invokePartyState(service, party, currentUserId);

		List<MatchPreparingMember> members = response.preparing().members();
		assertEquals(2, members.size());
		assertEquals(new MatchPreparingMember("나", "https://example.com/me.png", true), members.get(0));
		assertEquals(new MatchPreparingMember("상대", null, false), members.get(1));
	}

	@Test
	void PREPARING_상태를_같은_파티로_다시_조회해도_같은_members_목록과_순서를_반환한다() {
		long currentUserId = 1L;
		long otherUserId = 2L;
		MatchParty party = mock(MatchParty.class);
		when(party.getStatus()).thenReturn(MatchPartyStatus.PREPARING);
		when(party.getId()).thenReturn(10L);
		when(party.getPreparingStartedAt()).thenReturn(OPERATION_TIME.minusSeconds(10));

		MatchPartyParticipant currentParticipant = participant(10L, currentUserId);
		MatchPartyParticipant otherParticipant = participant(10L, otherUserId);

		MatchPartyParticipantRepository participantRepository = mock(MatchPartyParticipantRepository.class);
		when(participantRepository.findAllByIdPartyIdAndLeftAtIsNullOrderByCreatedAtAsc(10L))
			.thenReturn(List.of(currentParticipant, otherParticipant));

		UserQuery userQuery = mock(UserQuery.class);
		when(userQuery.findPublicProfilesByIds(List.of(currentUserId, otherUserId))).thenReturn(Map.of(
			currentUserId, new UserPublicProfile(currentUserId, "나", "https://example.com/me.png"),
			otherUserId, new UserPublicProfile(otherUserId, "상대", null)));

		MatchCurrentStateReadService service = service(participantRepository, userQuery);

		CurrentMatchStateResponse first = invokePartyState(service, party, currentUserId);
		CurrentMatchStateResponse second = invokePartyState(service, party, currentUserId);

		assertEquals(first.preparing().members(), second.preparing().members());
	}

	@Test
	void PREPARING_members_항목은_nickname_profileImageUrl_isMine_외의_필드를_포함하지_않는다() {
		RecordComponent[] components = MatchPreparingMember.class.getRecordComponents();

		assertEquals(3, components.length);
		assertTrue(java.util.Arrays.stream(components).map(RecordComponent::getName)
			.toList().containsAll(List.of("nickname", "profileImageUrl", "isMine")));
	}

	private MatchPartyParticipant participant(long partyId, long userId) {
		MatchPartyParticipant participant = mock(MatchPartyParticipant.class);
		MatchPartyParticipantId id = new MatchPartyParticipantId(partyId, userId);
		when(participant.getId()).thenReturn(id);
		return participant;
	}

	private CurrentMatchStateResponse invokePartyState(
		MatchCurrentStateReadService service, MatchParty party, long currentUserId) {
		try {
			var method = MatchCurrentStateReadService.class.getDeclaredMethod(
				"partyState", Instant.class, MatchParty.class, long.class);
			method.setAccessible(true);
			return (CurrentMatchStateResponse)method.invoke(service, OPERATION_TIME, party, currentUserId);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private MatchCurrentStateReadService service(
		MatchPartyParticipantRepository participantRepository, UserQuery userQuery) {
		return new MatchCurrentStateReadService(
			mock(MatchRequestRepository.class),
			mock(MatchProposalRepository.class),
			mock(MatchProposalMemberRepository.class),
			mock(MatchPartyRepository.class),
			participantRepository,
			mock(MatchChatSystemMessagePort.class),
			userQuery,
			mock(JdbcTemplate.class));
	}
}
