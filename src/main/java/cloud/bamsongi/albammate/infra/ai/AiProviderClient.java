package cloud.bamsongi.albammate.infra.ai;

interface AiProviderClient {

	AiProviderResponse propose(AiProviderPayload request);

	default String providerName() {
		return "fake";
	}
}
