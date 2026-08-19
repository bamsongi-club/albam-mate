package cloud.bamsongi.albammate.infra.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/** 기본 실행은 Redis ledger를 쓰고, 명시적 test profile만 결정적 in-memory 구현을 쓴다. */
@Configuration(proxyBeanMethods = false)
class AiQuotaRuntimeConfiguration {

	@Bean
	@Profile("test")
	AiQuotaLedger inMemoryAiQuotaLedger(AiCostWarningEventSink warningEventSink) {
		return new InMemoryAiQuotaLedger(warningEventSink);
	}

	@Bean
	@Profile({"local", "production"})
	AiQuotaLedger redisAiQuotaLedger(
		RedisConnectionFactory redisConnectionFactory,
		AiCostWarningEventSink warningEventSink) {
		return new RedisAiQuotaLedger(redisConnectionFactory, warningEventSink);
	}
}
