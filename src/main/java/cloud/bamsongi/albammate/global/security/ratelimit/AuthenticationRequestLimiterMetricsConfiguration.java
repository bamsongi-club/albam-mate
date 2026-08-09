package cloud.bamsongi.albammate.global.security.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;

/** 인증 요청 제한 메트릭을 운영 MeterRegistry에 연결한다. */
@Configuration(proxyBeanMethods = false)
class AuthenticationRequestLimiterMetricsConfiguration {

	@Bean
	AuthenticationRequestLimiterMetrics authenticationRequestLimiterMetrics(MeterRegistry meterRegistry) {
		return new AuthenticationRequestLimiterMetrics(meterRegistry);
	}
}
