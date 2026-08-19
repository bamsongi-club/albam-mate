package cloud.bamsongi.albammate.assistant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import cloud.bamsongi.albammate.game.contract.AssistantGameCandidateQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

class AssistantIntentOrchestrationServiceTest {

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
			revokedGate, providerDelegate, criteria -> java.util.List.of());

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> service.extract(991L, AssistantIntentRequest.forUser(
				"quota-subject-991", "협력 게임 추천", java.util.List.of())));

		assertEquals(ErrorCode.ASSISTANT_CONSENT_REQUIRED, exception.getErrorCode());
		assertEquals(0, providerDelegate.calls());
	}

	@Test
	void NEEDS_INPUT_후속_요청은_기존_조건을_보존해_후보_조회한다() {
		AssistantConsentGate grantedGate = new AssistantConsentGate() {
			@Override
			public boolean isGranted(long userId) {
				return true;
			}

			@Override
			public void requireGranted(long userId) {}
		};
		AssistantIntentExtractor extractor = mock(AssistantIntentExtractor.class);
		AssistantGameCandidateQuery candidateQuery = mock(AssistantGameCandidateQuery.class);
		when(extractor.extract(any())).thenReturn(new AssistantIntentExtraction(
			AssistantIntentStatus.SUCCESS,
			new AssistantIntentProposal("RECOMMEND", List.of()),
			null,
			false));
		when(candidateQuery.findCandidates(any())).thenReturn(List.of(new GameSummary(1L, 100L, "후보")));

		AssistantIntentOrchestrationService service = new AssistantIntentOrchestrationService(
			grantedGate, extractor, candidateQuery);
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
		assertEquals(previous, response.conditions());
		verify(candidateQuery).findCandidates(new AssistantGameCandidateQuery.Criteria(
			List.of(),
			List.of("DRAFTING"),
			List.of(),
			new BigDecimal("3.00"),
			"UP_TO_10",
			null,
			4));
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
