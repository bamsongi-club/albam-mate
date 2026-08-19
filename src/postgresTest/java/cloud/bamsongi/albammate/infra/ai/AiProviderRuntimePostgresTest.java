package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtraction;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentRequest;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest(properties = {
	"app.assistant.enabled=true"
})
class AiProviderRuntimePostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private AssistantIntentExtractor extractor;

	@Test
	void T1_PostgreSQL_Testcontainers_환경에서도_quota_구현이_없으면_provider_runtime이_fail_closed다() {
		AssistantIntentExtraction result = extractor.extract(AssistantIntentRequest.forUser(
			"postgres-fixture", "전략 게임 추천", List.of("GAME_STYLE")));

		assertInstanceOf(AiProviderIntentExtractor.class, extractor);
		assertEquals(AssistantIntentStatus.SERVICE_UNAVAILABLE, result.status());
	}
}
