package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

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
		when(gameRepository.findAssistantRecommendationCandidates()).thenReturn(List.of(candidate));

		var result = new AssistantExactGameNameQueryService(gameRepository)
			.findUniqueByNormalizedName("  카\u3000탄  ");

		assertEquals(java.util.Optional.of(candidate), result);
	}

	@Test
	void T2_부분별칭영문BGG문장부호와_복수_정규화_매치는_미매치다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		when(gameRepository.findAssistantRecommendationCandidates()).thenReturn(List.of(
			new AssistantRecommendationCandidate(1L, "카 탄", null, "설명"),
			new AssistantRecommendationCandidate(2L, "카\u3000탄", null, "설명")));
		var service = new AssistantExactGameNameQueryService(gameRepository);

		assertTrue(service.findUniqueByNormalizedName("카 탄").isEmpty());
		assertTrue(service.findUniqueByNormalizedName("카탄 추천").isEmpty());
		assertTrue(service.findUniqueByNormalizedName("Catan").isEmpty());
		assertTrue(service.findUniqueByNormalizedName("13").isEmpty());
		assertTrue(service.findUniqueByNormalizedName("카탄!").isEmpty());
	}
}
