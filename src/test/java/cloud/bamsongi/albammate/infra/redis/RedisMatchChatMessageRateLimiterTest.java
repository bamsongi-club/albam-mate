package cloud.bamsongi.albammate.infra.redis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;

/**
 * ADR-0080 축 1 — {@link RedisMatchChatMessageRateLimiter}가 ROOM의
 * {@link RedisChatMessageRateLimiter}와 같은 namespaceFor(Environment) 패턴을 쓰되 두 번째 bucket
 * key만 {@code party}로 분리하고, CHAT-T5 quota(사용자 5건·Party 30건/10초)를 Lua ARGV로 정확히 전달하는지
 * 확인한다.
 */
class RedisMatchChatMessageRateLimiterTest {

	private static final MatchChatMessageRateLimitProperties INJECTED_PROPERTIES = new MatchChatMessageRateLimitProperties(
		5, 30, Duration.ofSeconds(10));

	private StringRedisTemplate redisTemplate;
	private RedisMatchChatMessageRateLimiter limiter;

	@BeforeEach
	void setUp() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("local");
		limiter = new RedisMatchChatMessageRateLimiter(
			mock(RedisConnectionFactory.class), environment, INJECTED_PROPERTIES);
		redisTemplate = mock(StringRedisTemplate.class);
		ReflectionTestUtils.setField(limiter, "redisTemplate", redisTemplate);
	}

	@Test
	void local_namespace는_ROOM과_같은_prefix를_쓰되_party_key로_분리된다() {
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
	void 주입된_사용자_Party_창_크기가_Lua_ARGV로_전달된다() {
		stubExecute(List.of(1L, 0L));

		limiter.reserve(42L, 7L);

		ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
		Object[] args = argsCaptor.getValue();
		assertEquals("5", args[0]);
		assertEquals("30", args[1]);
		assertEquals("10000", args[2]);
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void key는_room이_아니라_party_segment를_사용한다() {
		stubExecute(List.of(1L, 0L));

		limiter.reserve(42L, 7L);

		ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
		verify(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), any(Object[].class));
		List<?> keys = keysCaptor.getValue();
		assertEquals("albam-mate:local:ratelimit:user:42", keys.get(0));
		assertEquals("albam-mate:local:ratelimit:party:7", keys.get(1));
		assertEquals("albam-mate:local:ratelimit:user:42:reservations", keys.get(2));
		assertEquals("albam-mate:local:ratelimit:party:7:reservations", keys.get(3));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void stubExecute(Object result) {
		doReturn(result).when(redisTemplate).execute(
			any(RedisScript.class), anyList(), any(Object[].class));
	}
}
