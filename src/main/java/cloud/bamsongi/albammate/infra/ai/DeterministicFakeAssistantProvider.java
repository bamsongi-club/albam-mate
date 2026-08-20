package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/** 네트워크·secret·provider 상태에 의존하지 않는 기본 provider다. */
final class DeterministicFakeAssistantProvider implements AiProviderClient {

	@Override
	public String providerName() {
		return "fake";
	}

	@Override
	public AiProviderResponse propose(AiProviderPayload request) {
		String sentence = request.currentUserSentence().toLowerCase(Locale.ROOT);
		if (sentence.contains("지원하지 않는") || sentence.contains("unsupported")) {
			return AiProviderResponse.success("UNSUPPORTED", List.of(), 7, 3, new BigDecimal("0.10"));
		}
		if (sentence.contains("전략") || sentence.contains("strategy")) {
			return AiProviderResponse.success("RECOMMEND", List.of("STRATEGY"), 7, 3, new BigDecimal("0.10"));
		}
		return AiProviderResponse.success("NEEDS_INPUT", List.of(), 7, 3, new BigDecimal("0.10"));
	}
}
