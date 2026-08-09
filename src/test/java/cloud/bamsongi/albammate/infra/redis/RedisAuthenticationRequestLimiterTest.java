package cloud.bamsongi.albammate.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.global.config.AuthenticationRequestProtectionProperties;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

class RedisAuthenticationRequestLimiterTest {

	private StringRedisTemplate redisTemplate;
	private RedisAuthenticationRequestLimiter limiter;

	@BeforeEach
	void setUp() {
		AuthenticationRequestProtectionProperties properties = new AuthenticationRequestProtectionProperties();
		properties.setWindow(Duration.ofSeconds(10));
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("local");
		limiter = new RedisAuthenticationRequestLimiter(mock(RedisConnectionFactory.class), environment, properties);
		redisTemplate = mock(StringRedisTemplate.class);
		ReflectionTestUtils.setField(limiter, "redisTemplate", redisTemplate);
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void T6_Redis_실패는_인메모리_fallback_없이_SERVICE_UNAVAILABLE로_끝난다() {
		doReturn(null).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

		BusinessException exception = assertThrows(BusinessException.class,
			() -> limiter.checkAndRecordSignup("203.0.113.99"));

		assertEquals(ErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void T7_Redis가_복구되면_이전_fallback_상태없이_다시_공용_결과를_사용한다() {
		doReturn(null).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
		assertThrows(BusinessException.class, () -> limiter.checkAndRecordLogin("203.0.113.100"));
		doReturn(List.of(1L, 0L)).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

		assertEquals(true, limiter.checkAndRecordLogin("203.0.113.100").allowed());
	}
}
