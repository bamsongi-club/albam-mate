package cloud.bamsongi.albammate.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.global.config.AuthenticationRequestProtectionProperties;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiterMetrics;
import cloud.bamsongi.albammate.global.security.ratelimit.LoginVerificationPermit;
import cloud.bamsongi.albammate.global.security.ratelimit.RateLimitDecision;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RedisAuthenticationRequestLimiterTest {

	private StringRedisTemplate redisTemplate;
	private RedisAuthenticationRequestLimiter limiter;

	@BeforeEach
	void setUp() {
		AuthenticationRequestProtectionProperties properties = new AuthenticationRequestProtectionProperties();
		properties.setWindow(Duration.ofSeconds(10));
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("local");
		limiter = new RedisAuthenticationRequestLimiter(
			mock(RedisConnectionFactory.class),
			environment,
			properties,
			AuthenticationRequestLimiterMetrics.global());
		redisTemplate = mock(StringRedisTemplate.class);
		ReflectionTestUtils.setField(limiter, "redisTemplate", redisTemplate);
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void T10_용량_거절은_고정_family와_reason_메트릭만_기록한다() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		Metrics.addRegistry(meterRegistry);
		try {
			doReturn(List.of(2L, 0L, 1L)).when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));

			RateLimitDecision decision = limiter.checkAndRecordSignup("203.0.113.106");
			BusinessException exception = assertThrows(BusinessException.class, decision::throwIfRejected);

			assertEquals(ErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
			assertEquals(1.0, meterRegistry.get("auth.request.limiter.rejections")
				.tags("family", "ip", "reason", "capacity_saturated").counter().count());
			assertEquals(0.0001, meterRegistry.get("auth.request.limiter.capacity.utilization")
				.tag("family", "ip").gauge().value());
			assertTrue(meterRegistry.getMeters().stream()
				.flatMap(meter -> meter.getId().getTags().stream())
				.map(Tag::getKey)
				.noneMatch(key -> key.contains("email") || key.contains("ip") || key.contains("digest")));
		} finally {
			Metrics.removeRegistry(meterRegistry);
			meterRegistry.close();
		}
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void T11_Redis_불능은_fail_closed_거절과_고정_메트릭으로_기록한다() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		Metrics.addRegistry(meterRegistry);
		try {
			doReturn(null).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

			BusinessException exception = assertThrows(BusinessException.class,
				() -> limiter.requireSignupAllowed("203.0.113.99"));

			assertEquals(ErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
			assertEquals(1.0, meterRegistry.get("auth.request.limiter.rejections")
				.tags("family", "ip", "reason", "redis_unavailable").counter().count());
		} finally {
			Metrics.removeRegistry(meterRegistry);
			meterRegistry.close();
		}
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void T7_Redis가_복구되면_이전_fallback_상태없이_다시_공용_결과를_사용한다() {
		doReturn(null).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
		assertThrows(BusinessException.class, () -> limiter.checkAndRecordLogin("203.0.113.100"));
		doReturn(List.of(1L, 0L, 1L)).when(redisTemplate).execute(any(RedisScript.class), anyList(),
			any(Object[].class));

		assertEquals(true, limiter.checkAndRecordLogin("203.0.113.100").allowed());
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void Redis_목록_응답이_계약과_다르면_SERVICE_UNAVAILABLE로_끝난다() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		Metrics.addRegistry(meterRegistry);
		try {
			doReturn(
				List.of(),
				List.of(1L),
				List.of("1", 0L, 1L),
				List.of(1L, "0", 1L),
				List.of(1L, 1L, 1L))
				.when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

			for (int index = 0; index < 5; index++) {
				assertUnavailable(() -> limiter.checkAndRecordSignup("203.0.113.101"));
			}
			assertEquals(5.0, meterRegistry.get("auth.request.limiter.rejections")
				.tags("family", "ip", "reason", "redis_unavailable").counter().count());
		} finally {
			Metrics.removeRegistry(meterRegistry);
			meterRegistry.close();
		}
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void Redis_단일값_응답이_계약과_다르면_SERVICE_UNAVAILABLE로_끝난다() {
		doReturn(2L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

		assertUnavailable(() -> limiter.resetLoginFailures("member@example.com", "203.0.113.102"));
		assertUnavailable(() -> limiter.tryAcquireLoginVerification("member@example.com", "203.0.113.102"));
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void Redis_TTL_밀리초는_초_단위로_올림한다() {
		doReturn(List.of(0L, 1_001L, 1L)).when(redisTemplate)
			.execute(any(RedisScript.class), anyList(), any(Object[].class));

		RateLimitDecision decision = limiter.checkAndRecordSignup("203.0.113.103");

		assertFalse(decision.allowed());
		assertEquals(2, decision.retryAfterSeconds());
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void Redis_gate_해제는_같은_permit에서_한_번만_실행한다() {
		doReturn(List.of(1L, 0L, 1L)).when(redisTemplate)
			.execute(any(RedisScript.class), anyList(), any(Object[].class));
		LoginVerificationPermit permit = limiter
			.tryAcquireLoginVerification("member@example.com", "203.0.113.104")
			.orElseThrow();

		permit.close();
		permit.close();

		verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(), any(Object[].class));
	}

	private void assertUnavailable(Executable executable) {
		BusinessException exception = assertThrows(BusinessException.class, executable);
		assertEquals(ErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
	}
}
