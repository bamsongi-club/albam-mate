package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.game.contract.AssistantExactGameNameMatch;
import cloud.bamsongi.albammate.game.contract.AssistantRecommendationCandidate;
import cloud.bamsongi.albammate.game.repository.GameRepository;

class AssistantExactGameNameQueryServiceTest {

	@Test
	void null과_blank_입력은_카탈로그를_조회하지_않고_미매치다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		var service = new AssistantExactGameNameQueryService(gameRepository);

		assertTrue(service.findUniqueByNormalizedName(null).isEmpty());
		assertTrue(service.findUniqueByNormalizedName("   ").isEmpty());

		verifyNoInteractions(gameRepository);
	}

	@Test
	void T1_NFKC_trim_공백축약_대소문자_정규화로_유일한_정식명만_찾는다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		AssistantRecommendationCandidate candidate = new AssistantRecommendationCandidate(
			1L, "카 탄", null, "공개 설명");
		when(gameRepository.findAssistantExactGameNameMatches()).thenReturn(
			List.of(new AssistantExactGameNameMatch(1L, "카 탄")));
		when(gameRepository.findAssistantRecommendationCandidateById(1L)).thenReturn(
			java.util.Optional.of(candidate));

		var result = new AssistantExactGameNameQueryService(gameRepository)
			.findUniqueByNormalizedName("  카\u3000탄  ");

		assertEquals(java.util.Optional.of(candidate), result);
		verify(gameRepository).findAssistantExactGameNameMatches();
		verify(gameRepository).findAssistantRecommendationCandidateById(1L);
		verifyNoMoreInteractions(gameRepository);
	}

	@Test
	void T2_부분별칭영문BGG문장부호와_복수_정규화_매치는_미매치다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		when(gameRepository.findAssistantExactGameNameMatches()).thenReturn(List.of(
			new AssistantExactGameNameMatch(1L, "카 탄"),
			new AssistantExactGameNameMatch(2L, "카\u3000탄")));
		var service = new AssistantExactGameNameQueryService(gameRepository);

		assertTrue(service.findUniqueByNormalizedName("카 탄").isEmpty());
		assertTrue(service.findUniqueByNormalizedName("카탄 추천").isEmpty());
		assertTrue(service.findUniqueByNormalizedName("Catan").isEmpty());
		assertTrue(service.findUniqueByNormalizedName("13").isEmpty());
		assertTrue(service.findUniqueByNormalizedName("카탄!").isEmpty());

		verify(gameRepository, times(5)).findAssistantExactGameNameMatches();
		verify(gameRepository, never()).findAssistantRecommendationCandidateById(
			org.mockito.ArgumentMatchers.anyLong());
		verifyNoMoreInteractions(gameRepository);
	}
}
