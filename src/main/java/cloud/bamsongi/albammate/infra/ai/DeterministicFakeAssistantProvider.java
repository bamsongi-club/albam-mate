package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.util.List;

/** 네트워크·secret·provider 상태에 의존하지 않는 기본 provider다. */
final class DeterministicFakeAssistantProvider implements AiProviderClient {

	@Override
	public String providerName() {
		return "fake";
	}

	@Override
	public AiProviderResponse propose(AiProviderPayload request) {
		return AiProviderResponse.success("RECOMMEND", List.of("STRATEGY"), 7, 3, new BigDecimal("0.10"));
	}
}
