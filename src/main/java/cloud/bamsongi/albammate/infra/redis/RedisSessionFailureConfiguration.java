package cloud.bamsongi.albammate.infra.redis;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;

/** local·production Spring Session 필터 앞에 Redis 장애 응답 변환기를 등록한다. */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "production"})
class RedisSessionFailureConfiguration {

	@Bean
	FilterRegistrationBean<RedisSessionFailureFilter> redisSessionFailureFilterRegistration(
		SecurityErrorResponseWriter responseWriter) {
		FilterRegistrationBean<RedisSessionFailureFilter> registration = new FilterRegistrationBean<>(
			new RedisSessionFailureFilter(responseWriter));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}
}
