package cloud.bamsongi.albammate.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * ADR-0080 축 1 — {@link RedisFixedWindowDualBucketRateLimiter}가 두 key·두 reservations key·두 limit·
 * window·reservationId를 Lua 실행에 그대로 넘기고, 그 원자 결과를 {@link RedisFixedWindowDualBucketRateLimiter.Decision}
 * 으로 정확히 판정하는지 확인한다. ROOM({@link RedisChatMessageRateLimiter})과 MATCH
 * ({@link RedisMatchChatMessageRateLimiter}) 양쪽이 이 판정 하나를 공유한다.
 */
class RedisFixedWindowDualBucketRateLimiterTest {

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void reserve는_두_key_두_reservations_key와_limit_window_reservationId를_그대로_전달한다() {
		stubExecute(List.of(1L, 0L));

		RedisFixedWindowDualBucketRateLimiter.Decision decision = RedisFixedWindowDualBucketRateLimiter.reserve(
			redisTemplate, "first", "second", "first:reservations", "second:reservations", 5, 30, 10_000L, "res-1");

		assertTrue(decision.allowed());
		assertEquals(0, decision.retryAfterMillis());
		ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());
		assertEquals(List.of("first", "second", "first:reservations", "second:reservations"), keysCaptor.getValue());
		Object[] args = argsCaptor.getValue();
		assertEquals("5", args[0]);
		assertEquals("30", args[1]);
		assertEquals("10000", args[2]);
		assertEquals("res-1", args[3]);
	}

	@Test
	void reserve는_초과_판정을_남은_TTL과_함께_반환한다() {
		stubExecute(List.of(0L, 1_500L));

		RedisFixedWindowDualBucketRateLimiter.Decision decision = RedisFixedWindowDualBucketRateLimiter.reserve(
			redisTemplate, "first", "second", "first:reservations", "second:reservations", 5, 30, 10_000L, "res-1");

		assertFalse(decision.allowed());
		assertEquals(1_500L, decision.retryAfterMillis());
	}

	@Test
	void reserve는_null이거나_형태가_어긋난_결과를_불명확한_판정으로_처리한다() {
		stubExecute(null);
		assertAmbiguous(RedisFixedWindowDualBucketRateLimiter.reserve(
			redisTemplate, "first", "second", "first:reservations", "second:reservations", 5, 30, 10_000L, "res-1"));

		stubExecute(List.of("invalid", 0L));
		assertAmbiguous(RedisFixedWindowDualBucketRateLimiter.reserve(
			redisTemplate, "first", "second", "first:reservations", "second:reservations", 5, 30, 10_000L, "res-1"));

		stubExecute(List.of(2L, 0L));
		assertAmbiguous(RedisFixedWindowDualBucketRateLimiter.reserve(
			redisTemplate, "first", "second", "first:reservations", "second:reservations", 5, 30, 10_000L, "res-1"));
	}

	@Test
	void release는_reservationId만_ARGV로_전달하고_1이면_true를_반환한다() {
		stubExecute(1L);

		boolean released = RedisFixedWindowDualBucketRateLimiter.release(
			redisTemplate, "first", "second", "first:reservations", "second:reservations", "res-1");

		assertTrue(released);
		ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
		verify(redisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
		assertEquals("res-1", argsCaptor.getValue()[0]);
	}

	@Test
	void release는_null이거나_1이_아니면_false를_반환한다() {
		stubExecute(null);
		assertFalse(RedisFixedWindowDualBucketRateLimiter.release(
			redisTemplate, "first", "second", "first:reservations", "second:reservations", "res-1"));

		stubExecute(0L);
		assertFalse(RedisFixedWindowDualBucketRateLimiter.release(
			redisTemplate, "first", "second", "first:reservations", "second:reservations", "res-1"));
	}

	private void assertAmbiguous(RedisFixedWindowDualBucketRateLimiter.Decision decision) {
		assertFalse(decision.allowed());
		assertEquals(0, decision.retryAfterMillis());
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void stubExecute(Object result) {
		doReturn(result).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
	}
}
