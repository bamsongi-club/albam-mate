package cloud.bamsongi.albammate.assistant.service;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.assistant.contract.AssistantConsentGate;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtraction;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentRequest;
import lombok.RequiredArgsConstructor;

/** 사용자 동의 확인을 provider port 진입보다 먼저 수행하는 AI-01 요청 경계다. */
@Service
@RequiredArgsConstructor
public class AssistantIntentOrchestrationService {

	private final AssistantConsentGate assistantConsentGate;
	private final AssistantIntentExtractor assistantIntentExtractor;

	public AssistantIntentExtraction extract(long userId, AssistantIntentRequest request) {
		assistantConsentGate.requireGranted(userId);
		return assistantIntentExtractor.extract(request);
	}
}
