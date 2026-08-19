package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class AiQuotaRuntimeConfigurationTest {

	@Test
	void T1_quota_runtime은_test용_결정적_ledger와_운영용_Redis_ledger를_분리한다() {
		AiQuotaRuntimeConfiguration configuration = new AiQuotaRuntimeConfiguration();
		AiCostWarningEventSink sink = event -> {};

		assertInstanceOf(InMemoryAiQuotaLedger.class, configuration.inMemoryAiQuotaLedger(sink));
		assertInstanceOf(RedisAiQuotaLedger.class,
			configuration.redisAiQuotaLedger(mock(RedisConnectionFactory.class), sink));
	}
}
