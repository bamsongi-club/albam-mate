package cloud.bamsongi.albammate.infra.redis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;

class RedisChatMessageRateLimiterTest {

	private static final ChatMessageRateLimitProperties INJECTED_PROPERTIES = new ChatMessageRateLimitProperties(7, 11,
		Duration.ofSeconds(4));

	private StringRedisTemplate redisTemplate;
	private RedisChatMessageRateLimiter limiter;

	@BeforeEach
	void setUp() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("local");
		limiter = new RedisChatMessageRateLimiter(
			mock(RedisConnectionFactory.class), environment, INJECTED_PROPERTIES);
		redisTemplate = mock(StringRedisTemplate.class);
		ReflectionTestUtils.setField(limiter, "redisTemplate", redisTemplate);
	}

	@Test
	void T3_local_rate_limit_namespace는_production과_분리된다() {
		assertEquals("albam-mate:local:ratelimit", ReflectionTestUtils.getField(limiter, "keyPrefix"));
	}

	@Test
	void 허용_예약은_release에서_두_bucket을_보상한다() {
		stubExecute(List.of(1L, 0L));
		var reservation = limiter.reserve(42L, 7L);
		reset(redisTemplate);
		stubExecute(1L);

		assertDoesNotThrow(reservation::release);
	}

	@Test
	void 제한_초과와_불명확한_Redis_결과는_계약된_예외가_된다() {
		stubExecute(List.of(0L, 1_500L));
		assertThrows(RateLimitExceededException.class, () -> limiter.reserve(42L, 7L));

		stubExecute(null);
		assertThrows(BusinessException.class, () -> limiter.reserve(42L, 7L));

		stubExecute(List.of("invalid", 0L));
		assertThrows(BusinessException.class, () -> limiter.reserve(42L, 7L));

		stubExecute(List.of(2L, 0L));
		assertThrows(BusinessException.class, () -> limiter.reserve(42L, 7L));
	}

	@Test
	void 잘못된_Retry_After_값은_서비스_불가로_실패한다() {
		stubExecute(List.of(0L, Long.MAX_VALUE));

		assertThrows(BusinessException.class, () -> limiter.reserve(42L, 7L));
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void T4_주입된_사용자_방_창_크기가_Lua_ARGV로_전달된다() {
		stubExecute(List.of(1L, 0L));

		limiter.reserve(42L, 7L);

		ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
		Object[] args = argsCaptor.getValue();
		assertEquals("7", args[0]);
		assertEquals("11", args[1]);
		assertEquals("4000", args[2]);
	}

	@Test
	void T5_사용자_방_허용량과_창_크기가_최소값_미만이면_기동이_바인딩_실패로_끝난다() {
		contextRunnerWith("app.chat.rate-limit.user-limit=0")
			.run(context -> assertNotNull(context.getStartupFailure()));
		contextRunnerWith("app.chat.rate-limit.room-limit=0")
			.run(context -> assertNotNull(context.getStartupFailure()));
		contextRunnerWith("app.chat.rate-limit.window=0s")
			.run(context -> assertNotNull(context.getStartupFailure()));
		contextRunnerWith()
			.run(context -> assertNull(context.getStartupFailure()));
	}

	private ApplicationContextRunner contextRunnerWith(String... properties) {
		return new ApplicationContextRunner()
			.withUserConfiguration(ChatMessageRateLimitPropertiesConfiguration.class)
			.withPropertyValues(properties);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ChatMessageRateLimitProperties.class)
	static class ChatMessageRateLimitPropertiesConfiguration {}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void stubExecute(Object result) {
		doReturn(result).when(redisTemplate).execute(
			any(RedisScript.class), anyList(), any(Object[].class));
	}
}
