package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import cloud.bamsongi.albammate.matching.service.query.MatchPartyParticipantRefQueryService;

@ExtendWith(MockitoExtension.class)
class MatchPartyParticipantRefQueryServiceTest {

	private static final Instant FIXED_TIME = Instant.parse("2026-08-19T00:00:00Z");

	@Mock
	private MatchPartyParticipantRepository participantRepository;
	@InjectMocks
	private MatchPartyParticipantRefQueryService participantRefQueryService;

	@Test
	void 참가한_적_있는_사용자의_단건_조회는_opaque_participantRef_문자열을_반환한다() {
		UUID participantRef = UUID.fromString("00000000-0000-0000-0000-000000000201");
		MatchPartyParticipant participant = MatchPartyParticipant.create(10L, 1L, participantRef, FIXED_TIME);
		when(participantRepository.findParticipantByPartyIdAndUserId(10L, 1L)).thenReturn(Optional.of(participant));

		assertEquals(
			Optional.of("00000000-0000-0000-0000-000000000201"),
			participantRefQueryService.findParticipantRef(10L, 1L));
	}

	@Test
	void 참가한_적_없는_조합의_단건_조회는_예외_없이_빈_값이다() {
		when(participantRepository.findParticipantByPartyIdAndUserId(10L, 404L)).thenReturn(Optional.empty());

		assertTrue(participantRefQueryService.findParticipantRef(10L, 404L).isEmpty());
	}

	@Test
	void 배치_조회는_저장소가_반환한_참가자만_userId로_매핑한다() {
		UUID firstRef = UUID.fromString("00000000-0000-0000-0000-000000000202");
		UUID secondRef = UUID.fromString("00000000-0000-0000-0000-000000000203");
		MatchPartyParticipant first = MatchPartyParticipant.create(10L, 1L, firstRef, FIXED_TIME);
		MatchPartyParticipant second = MatchPartyParticipant.create(10L, 2L, secondRef, FIXED_TIME);
		when(participantRepository.findParticipantsByPartyIdAndUserIds(10L, List.of(1L, 2L, 999L)))
			.thenReturn(List.of(first, second));

		assertEquals(
			Map.of(
				1L, "00000000-0000-0000-0000-000000000202",
				2L, "00000000-0000-0000-0000-000000000203"),
			participantRefQueryService.findParticipantRefs(10L, List.of(1L, 2L, 999L)));
	}

	@Test
	void 없는_사용자가_섞여도_배치_조회는_예외를_던지지_않고_그_항목만_제외한다() {
		UUID firstRef = UUID.fromString("00000000-0000-0000-0000-000000000204");
		MatchPartyParticipant first = MatchPartyParticipant.create(10L, 1L, firstRef, FIXED_TIME);
		when(participantRepository.findParticipantsByPartyIdAndUserIds(10L, List.of(1L, 999L)))
			.thenReturn(List.of(first));

		Map<Long, String> result = participantRefQueryService.findParticipantRefs(10L, List.of(1L, 999L));

		assertEquals(Map.of(1L, "00000000-0000-0000-0000-000000000204"), result);
		assertTrue(!result.containsKey(999L));
	}

	@Test
	void 빈_userId_컬렉션의_배치_조회는_저장소를_조회하지_않고_빈_Map을_반환한다() {
		assertEquals(Map.of(), participantRefQueryService.findParticipantRefs(10L, List.of()));

		verifyNoInteractions(participantRepository);
	}

	@Test
	void 단건_조회는_findParticipantByPartyIdAndUserId에만_위임한다() {
		when(participantRepository.findParticipantByPartyIdAndUserId(10L, 1L)).thenReturn(Optional.empty());

		participantRefQueryService.findParticipantRef(10L, 1L);

		verify(participantRepository).findParticipantByPartyIdAndUserId(10L, 1L);
		verifyNoMoreInteractions(participantRepository);
	}
}
