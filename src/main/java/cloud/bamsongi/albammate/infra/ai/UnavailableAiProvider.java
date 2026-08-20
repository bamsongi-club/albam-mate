package cloud.bamsongi.albammate.infra.ai;

/** 외부 provider 준비 조건이 충족되지 않았을 때 호출을 차단하는 fail-closed provider다. */
final class UnavailableAiProvider implements AiProviderClient {

	@Override
	public String providerName() {
		return "unavailable";
	}

	@Override
	public AiProviderResponse propose(AiProviderPayload request) {
		return AiProviderResponse.failure(AiProviderFailure.SERVICE_UNAVAILABLE);
	}
}
