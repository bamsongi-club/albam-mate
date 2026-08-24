package cloud.bamsongi.albammate.assistant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.assistant.contract.AssistantConsentGate;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtraction;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentProposal;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentRequest;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;
import cloud.bamsongi.albammate.assistant.dto.AssistantConditionSummary;
import cloud.bamsongi.albammate.assistant.dto.AssistantRecommendationRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantRecommendationState;
import cloud.bamsongi.albammate.game.contract.AssistantExactGameNameQuery;
import cloud.bamsongi.albammate.game.contract.AssistantGameCandidateQuery;
import cloud.bamsongi.albammate.game.contract.AssistantRecommendationCandidate;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

class AssistantIntentOrchestrationServiceTest {

	@Test
	void T3_GameSummary는_AI_후보DTO로_확장하지_않는다() {
		assertEquals(List.of("id", "bggId", "name"),
			java.util.Arrays.stream(GameSummary.class.getRecordComponents()).map(component -> component.getName())
				.toList());
	}

	@Test
	void T1_동의거절은_feature보다_먼저_실패하고_resolver와_provider를_호출하지_않는다() {
		AssistantIntentExtractor extractor = mock(AssistantIntentExtractor.class);
		AssistantExactGameNameQuery exactQuery = mock(AssistantExactGameNameQuery.class);
		AssistantConsentGate deniedGate = new AssistantConsentGate() {
			@Override
			public boolean isGranted(long userId) {
				return false;
			}

			@Override
			public void requireGranted(long userId) {
				throw new BusinessException(ErrorCode.ASSISTANT_CONSENT_REQUIRED);
			}
		};
		AssistantIntentOrchestrationService service = new AssistantIntentOrchestrationService(
			deniedGate, new AssistantConsentProperties(), exactQuery, extractor,
			mock(AssistantGameCandidateQuery.class));

		BusinessException error = assertThrows(BusinessException.class,
			() -> service.recommend(1L, new AssistantRecommendationRequest("카 탄", null)));

		assertEquals(ErrorCode.ASSISTANT_CONSENT_REQUIRED, error.getErrorCode());
		verifyNoInteractions(exactQuery, extractor);
	}

	@Test
	void T1_feature_비활성은_resolver와_provider를_호출하지_않는다() {
		AssistantIntentExtractor extractor = mock(AssistantIntentExtractor.class);
		AssistantExactGameNameQuery exactQuery = mock(AssistantExactGameNameQuery.class);
		AssistantIntentOrchestrationService service = new AssistantIntentOrchestrationService(
			grantedGate(), new AssistantConsentProperties(), exactQuery, extractor,
			mock(AssistantGameCandidateQuery.class));

		BusinessException error = assertThrows(BusinessException.class,
			() -> service.recommend(1L, new AssistantRecommendationRequest("카 탄", null)));

		assertEquals(ErrorCode.ASSISTANT_NOT_ENABLED, error.getErrorCode());
		verifyNoInteractions(exactQuery, extractor);
	}

	@Test
	void T2_정확매치가_아니면_provider를_한번만_호출하고_direct_gameId를_만들지_않는다() {
		AssistantIntentExtractor extractor = mock(AssistantIntentExtractor.class);
		AssistantExactGameNameQuery exactQuery = mock(AssistantExactGameNameQuery.class);
		AssistantGameCandidateQuery candidateQuery = mock(AssistantGameCandidateQuery.class);
		when(exactQuery.findUniqueByNormalizedName("카탄!")).thenReturn(java.util.Optional.empty());
		when(extractor.extract(any())).thenReturn(new AssistantIntentExtraction(AssistantIntentStatus.SUCCESS,
			new AssistantIntentProposal("RECOMMEND", List.of("STRATEGY")), null, false));
		when(candidateQuery.findCandidates(any())).thenReturn(List.of());
		var service = new AssistantIntentOrchestrationService(grantedGate(), grantableProperties(), exactQuery,
			extractor, candidateQuery);

		var response = service.recommend(1L, new AssistantRecommendationRequest("카탄!", null));

		assertEquals(null, response.conditions().gameId());
		verify(extractor).extract(any());
		verifyNoMoreInteractions(extractor);
	}

	@Test
	void 철회된_동의는_provider_delegate를_호출하지_않는다() {
		CountingIntentExtractor providerDelegate = new CountingIntentExtractor();
		AssistantConsentGate revokedGate = new AssistantConsentGate() {
			@Override
			public boolean isGranted(long userId) {
				return false;
			}

			@Override
			public void requireGranted(long userId) {
				throw new BusinessException(ErrorCode.ASSISTANT_CONSENT_REQUIRED);
			}
		};
		AssistantIntentOrchestrationService service = new AssistantIntentOrchestrationService(
			revokedGate, grantableProperties(), name -> java.util.Optional.empty(), providerDelegate,
			criteria -> java.util.List.of());

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> service.extract(991L, AssistantIntentRequest.forUser(
				"quota-subject-991", "협력 게임 추천", java.util.List.of())));

		assertEquals(ErrorCode.ASSISTANT_CONSENT_REQUIRED, exception.getErrorCode());
		assertEquals(0, providerDelegate.calls());
	}

	@Test
	void T1_유일한_정규화_정식명은_provider_없이_추천으로_끝난다() {
		AssistantConsentGate grantedGate = grantedGate();
		AssistantIntentExtractor extractor = mock(AssistantIntentExtractor.class);
		AssistantExactGameNameQuery exactGameNameQuery = mock(AssistantExactGameNameQuery.class);
		AssistantGameCandidateQuery candidateQuery = mock(AssistantGameCandidateQuery.class);
		when(exactGameNameQuery.findUniqueByNormalizedName(any())).thenReturn(
			java.util.Optional.of(new AssistantRecommendationCandidate(7L, "카탄", null, "공개 설명")));

		AssistantIntentOrchestrationService service = new AssistantIntentOrchestrationService(
			grantedGate, grantableProperties(), exactGameNameQuery, extractor, candidateQuery);

		AssistantConditionSummary priorConditions = new AssistantConditionSummary(
			List.of("STRATEGY"), List.of(), List.of(), null, null, null, 4, null, null, null);
		var response = service.recommend(1L, new AssistantRecommendationRequest("  카\u3000탄  ", priorConditions));

		assertEquals(AssistantRecommendationState.RECOMMENDED, response.state());
		assertEquals(7L, response.conditions().gameId());
		assertEquals(List.of("STRATEGY"), response.conditions().categories());
		assertEquals(List.of(new AssistantRecommendationCandidate(7L, "카탄", null, "공개 설명")),
			response.candidates());
		verify(candidateQuery).validateCriteria(new AssistantGameCandidateQuery.Criteria(
			List.of("STRATEGY"), List.of(), List.of(), null, null, 7L, 4));
		verifyNoMoreInteractions(extractor, candidateQuery);
	}

	@Test
	void T2_provider_구조화_조건을_후속_조건과_병합해_모든_후보_필터로_전달한다() {
		AssistantConsentGate grantedGate = grantedGate();
		AssistantIntentExtractor extractor = mock(AssistantIntentExtractor.class);
		AssistantExactGameNameQuery exactGameNameQuery = mock(AssistantExactGameNameQuery.class);
		AssistantGameCandidateQuery candidateQuery = mock(AssistantGameCandidateQuery.class);
		when(exactGameNameQuery.findUniqueByNormalizedName("다른 조건")).thenReturn(java.util.Optional.empty());
		when(extractor.extract(any())).thenReturn(new AssistantIntentExtraction(
			AssistantIntentStatus.SUCCESS,
			new AssistantIntentProposal(
				"RECOMMEND", List.of("STRATEGY"), List.of(), List.of("HORROR"), null, null, 4),
			null,
			false));
		when(candidateQuery.findCandidates(any())).thenReturn(
			List.of(new AssistantRecommendationCandidate(1L, "후보", null, "공개 설명")));

		AssistantIntentOrchestrationService service = new AssistantIntentOrchestrationService(
			grantedGate, grantableProperties(), exactGameNameQuery, extractor, candidateQuery);
		AssistantConditionSummary previous = new AssistantConditionSummary(
			List.of(),
			List.of("DRAFTING"),
			List.of(),
			new BigDecimal("3.00"),
			"UP_TO_10",
			null,
			4,
			null,
			null,
			null);

		var response = service.recommend(1L, new AssistantRecommendationRequest("다른 조건", previous));

		assertEquals(AssistantRecommendationState.RECOMMENDED, response.state());
		assertEquals(List.of("STRATEGY"), response.conditions().categories());
		assertEquals(List.of("DRAFTING"), response.conditions().mechanisms());
		assertEquals(List.of("HORROR"), response.conditions().themes());
		assertEquals(new BigDecimal("3.00"), response.conditions().complexityMax());
		assertEquals("UP_TO_10", response.conditions().playTimeMax());
		assertEquals(4, response.conditions().playerCount());
		verify(candidateQuery).findCandidates(new AssistantGameCandidateQuery.Criteria(
			List.of("STRATEGY"), List.of("DRAFTING"), List.of("HORROR"), new BigDecimal("3.00"), "UP_TO_10", null,
			4));
		verify(candidateQuery).validateCriteria(new AssistantGameCandidateQuery.Criteria(
			List.of("STRATEGY"), List.of("DRAFTING"), List.of("HORROR"), new BigDecimal("3.00"), "UP_TO_10", null,
			4));
		verifyNoMoreInteractions(candidateQuery);
	}

	private AssistantConsentGate grantedGate() {
		return new AssistantConsentGate() {
			@Override
			public boolean isGranted(long userId) {
				return true;
			}

			@Override
			public void requireGranted(long userId) {}
		};
	}

	private AssistantConsentProperties grantableProperties() {
		AssistantConsentProperties properties = new AssistantConsentProperties();
		properties.setEnabled(true);
		properties.setProvider("fake");
		properties.setNoRetentionVerified(true);
		properties.setNoTrainingVerified(true);
		properties.setPolicyVersion("policy");
		properties.setPolicyUrl("https://example.com/policy");
		return properties;
	}

	private static final class CountingIntentExtractor implements AssistantIntentExtractor {

		private int calls;

		@Override
		public AssistantIntentExtraction extract(AssistantIntentRequest request) {
			calls++;
			return new AssistantIntentExtraction(AssistantIntentStatus.SUCCESS, null, null, false);
		}

		int calls() {
			return calls;
		}
	}
}
