package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtraction;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentRequest;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;

@Testcontainers
@SpringBootTest(properties = {
	"app.assistant.enabled=true"
})
class AiProviderRuntimePostgresTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
		.withDatabaseName("albam_mate_ai_test");

	@Autowired
	private AssistantIntentExtractor extractor;

	@Test
	void T1_PostgreSQL_Testcontainers_환경에서도_기본_fake_provider_wiring이_외부호출없이_기동된다() {
		AssistantIntentExtraction result = extractor.extract(AssistantIntentRequest.forUser(
			"postgres-fixture", "전략 게임 추천", List.of("GAME_STYLE")));

		assertInstanceOf(AiProviderIntentExtractor.class, extractor);
		assertEquals(AssistantIntentStatus.SUCCESS, result.status());
		assertEquals("fake", result.usage().provider());
	}
}
