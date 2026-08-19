package cloud.bamsongi.albammate.assistant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.assistant.contract.AssistantConsentGate;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtraction;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentRequest;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;
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
			revokedGate, providerDelegate);

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> service.extract(991L, AssistantIntentRequest.forUser(
				"quota-subject-991", "협력 게임 추천", java.util.List.of())));

		assertEquals(ErrorCode.ASSISTANT_CONSENT_REQUIRED, exception.getErrorCode());
		assertEquals(0, providerDelegate.calls());
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
