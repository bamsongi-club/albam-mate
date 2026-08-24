package cloud.bamsongi.albammate.global.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.global.config.AuthenticationRequestProtectionProperties;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

class InMemoryAuthenticationRequestLimiterTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
	private InMemoryAuthenticationRequestLimiter limiter;

	@BeforeEach
	void setUp() {
		limiter = new InMemoryAuthenticationRequestLimiter(properties(), clock);
	}

	@Test
	void 회원가입_IP_제한은_허용량까지_기록하고_초과분의_재시도_초를_반환한다() {
		for (int i = 0; i < 5; i++) {
			assertTrue(limiter.checkAndRecordSignup("203.0.113.10").allowed());
		}

		RateLimitDecision rejected = limiter.checkAndRecordSignup("203.0.113.10");

		assertFalse(rejected.allowed());
		assertEquals(10, rejected.retryAfterSeconds());
		assertEquals(1, limiter.ipBucketCount());
	}

	@Test
	void T1_같은_IP의_signup과_login은_논리_IP_슬롯_하나를_공유한다() {
		String remoteIp = "203.0.113.19";
		for (int i = 0; i < 30; i++) {
			assertTrue(limiter.checkAndRecordLogin(remoteIp).allowed());
		}

		RateLimitDecision rejectedLogin = limiter.checkAndRecordLogin(remoteIp);

		assertFalse(rejectedLogin.allowed());
		assertEquals(10, rejectedLogin.retryAfterSeconds());
		for (int i = 0; i < 5; i++) {
			assertTrue(limiter.checkAndRecordSignup(remoteIp).allowed());
		}
		assertFalse(limiter.checkAndRecordSignup(remoteIp).allowed());
		assertEquals(1, limiter.ipBucketCount());
	}

	@Test
	void T2_IP_슬롯이_포화되면_신규_IP는_SERVICE_UNAVAILABLE로_거절한다() {
		AuthenticationRequestProtectionProperties properties = properties();
		properties.setMaxIpKeys(1);
		InMemoryAuthenticationRequestLimiter saturated = new InMemoryAuthenticationRequestLimiter(properties, clock);
		assertTrue(saturated.checkAndRecordSignup("203.0.113.30").allowed());

		BusinessException exception = assertThrows(BusinessException.class,
			() -> saturated.requireSignupAllowed("203.0.113.31"));

		assertEquals(ErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
		assertEquals(1, saturated.ipBucketCount());
	}

	@Test
	void T3_IP_슬롯_포화_뒤에도_기존_IP는_축출되지_않고_429_규칙을_계속_적용한다() {
		AuthenticationRequestProtectionProperties properties = properties();
		properties.setMaxIpKeys(1);
		InMemoryAuthenticationRequestLimiter saturated = new InMemoryAuthenticationRequestLimiter(properties, clock);
		for (int index = 0; index < 5; index++) {
			assertTrue(saturated.checkAndRecordSignup("203.0.113.32").allowed());
		}

		assertThrows(BusinessException.class, () -> saturated.requireSignupAllowed("203.0.113.33"));
		RateLimitDecision existing = saturated.checkAndRecordSignup("203.0.113.32");

		assertFalse(existing.allowed());
		assertEquals(10, existing.retryAfterSeconds());
		assertEquals(1, saturated.ipBucketCount());
	}

	@Test
	void T4_동시_신규_IP_요청도_논리_IP_등록_상한을_넘지_않는다() throws Exception {
		AuthenticationRequestProtectionProperties properties = properties();
		properties.setMaxIpKeys(2);
		InMemoryAuthenticationRequestLimiter saturated = new InMemoryAuthenticationRequestLimiter(properties, clock);
		ExecutorService executor = Executors.newFixedThreadPool(10);
		try {
			List<Future<RateLimitDecision>> futures = new ArrayList<>();
			for (int index = 0; index < 10; index++) {
				int suffix = index;
				futures.add(executor.submit(() -> saturated.checkAndRecordSignup("203.0.113." + (40 + suffix))));
			}
			long allowed = futures.stream().filter(future -> get(future).allowed()).count();

			assertEquals(2, allowed);
			assertEquals(2, saturated.ipBucketCount());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void T5_만료된_IP_상태는_논리_슬롯을_반납한다() {
		AuthenticationRequestProtectionProperties properties = properties();
		properties.setMaxIpKeys(1);
		InMemoryAuthenticationRequestLimiter saturated = new InMemoryAuthenticationRequestLimiter(properties, clock);
		assertTrue(saturated.checkAndRecordSignup("203.0.113.51").allowed());
		assertThrows(BusinessException.class, () -> saturated.requireLoginAllowed("203.0.113.52"));
		clock.advance(Duration.ofSeconds(10));

		assertTrue(saturated.checkAndRecordLogin("203.0.113.52").allowed());
		assertEquals(1, saturated.ipBucketCount());
	}

	@Test
	void 이동_창이_만료되면_같은_IP를_다시_허용한다() {
		for (int i = 0; i < 5; i++) {
			limiter.checkAndRecordSignup("203.0.113.11");
		}
		clock.advance(Duration.ofSeconds(10));

		assertTrue(limiter.checkAndRecordSignup("203.0.113.11").allowed());
		assertEquals(1, limiter.ipBucketCount());
	}

	@Test
	void 로그인_실패는_성공하면_초기화되고_다시_허용된다() {
		for (int i = 0; i < 5; i++) {
			assertTrue(limiter.recordLoginFailure("user@example.com", "203.0.113.12").allowed());
		}
		assertFalse(limiter.checkLoginFailureAllowed("user@example.com", "203.0.113.12").allowed());

		limiter.resetLoginFailures("user@example.com", "203.0.113.12");

		assertTrue(limiter.checkLoginFailureAllowed("user@example.com", "203.0.113.12").allowed());
		assertEquals(0, limiter.loginFailureBucketCount());
	}

	@Test
	void T6_동일_이메일_IP의_실패_버킷과_gate는_하나의_실패_슬롯을_공유한다() {
		AuthenticationRequestProtectionProperties properties = properties();
		properties.setMaxFailureKeys(1);
		InMemoryAuthenticationRequestLimiter saturated = new InMemoryAuthenticationRequestLimiter(properties, clock);
		assertTrue(saturated.recordLoginFailure("user@example.com", "203.0.113.13").allowed());
		LoginVerificationPermit permit = saturated.tryAcquireLoginVerification("user@example.com", "203.0.113.13")
			.orElseThrow();

		assertThrows(BusinessException.class,
			() -> saturated.tryAcquireLoginVerification("other@example.com", "203.0.113.14"));
		assertEquals(1, saturated.loginFailureBucketCount());
		assertEquals(1, saturated.activeLoginVerificationCount());
		permit.close();
	}

	@Test
	void T7_실패_슬롯_포화여도_확인과_반납은_허용하고_새_gate만_거절한다() {
		AuthenticationRequestProtectionProperties properties = properties();
		properties.setMaxFailureKeys(1);
		InMemoryAuthenticationRequestLimiter saturated = new InMemoryAuthenticationRequestLimiter(properties, clock);
		assertTrue(saturated.recordLoginFailure("first@example.com", "203.0.113.14").allowed());

		assertTrue(saturated.checkLoginFailureAllowed("second@example.com", "203.0.113.15").allowed());
		assertThrows(BusinessException.class,
			() -> saturated.tryAcquireLoginVerification("second@example.com", "203.0.113.15"));
		saturated.resetLoginFailures("first@example.com", "203.0.113.14");
		assertTrue(saturated.tryAcquireLoginVerification("second@example.com", "203.0.113.15").isPresent());
	}

	@Test
	void T7_포화된_실패_슬롯에서_신규_실패_기록은_SERVICE_UNAVAILABLE로_거절한다() {
		AuthenticationRequestProtectionProperties properties = properties();
		properties.setMaxFailureKeys(1);
		InMemoryAuthenticationRequestLimiter saturated = new InMemoryAuthenticationRequestLimiter(properties, clock);
		assertTrue(saturated.recordLoginFailure("first@example.com", "203.0.113.151").allowed());

		RateLimitDecision rejected = saturated.recordLoginFailure("second@example.com", "203.0.113.152");

		assertFalse(rejected.allowed());
		assertEquals(0, rejected.retryAfterSeconds());
		BusinessException exception = assertThrows(BusinessException.class, rejected::throwIfRejected);
		assertEquals(ErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
	}

	@Test
	void T8_실패와_gate가_모두_없어지면_논리_실패_슬롯을_즉시_반납한다() {
		AuthenticationRequestProtectionProperties properties = properties();
		properties.setMaxFailureKeys(1);
		InMemoryAuthenticationRequestLimiter saturated = new InMemoryAuthenticationRequestLimiter(properties, clock);
		assertTrue(saturated.recordLoginFailure("first@example.com", "203.0.113.16").allowed());
		LoginVerificationPermit permit = saturated.tryAcquireLoginVerification("first@example.com", "203.0.113.16")
			.orElseThrow();

		saturated.resetLoginFailures("first@example.com", "203.0.113.16");
		assertThrows(BusinessException.class,
			() -> saturated.tryAcquireLoginVerification("second@example.com", "203.0.113.17"));
		permit.close();

		assertTrue(saturated.tryAcquireLoginVerification("second@example.com", "203.0.113.17").isPresent());
	}

	@Test
	void T9_인메모리_구현은_유효_상태를_축출하지_않는다() {
		AuthenticationRequestProtectionProperties properties = properties();
		properties.setMaxIpKeys(1);
		InMemoryAuthenticationRequestLimiter saturated = new InMemoryAuthenticationRequestLimiter(properties, clock);
		for (int index = 0; index < 5; index++) {
			assertTrue(saturated.checkAndRecordSignup("203.0.113.18").allowed());
		}

		assertThrows(BusinessException.class, () -> saturated.requireLoginAllowed("203.0.113.19"));
		assertFalse(saturated.checkAndRecordSignup("203.0.113.18").allowed());
	}

	@Test
	void 동일한_로그인_키는_검증_게이트를_하나만_획득한다() {
		LoginVerificationPermit first = limiter.tryAcquireLoginVerification("user@example.com", "203.0.113.16")
			.orElseThrow();

		Optional<LoginVerificationPermit> second = limiter.tryAcquireLoginVerification("user@example.com",
			"203.0.113.16");

		assertTrue(second.isEmpty());
		assertEquals(1, limiter.activeLoginVerificationCount());
		first.close();
		first.close();
		assertEquals(0, limiter.activeLoginVerificationCount());
		assertTrue(
			limiter.tryAcquireLoginVerification("user@example.com", "203.0.113.16")
				.isPresent());
	}

	@Test
	void 로그인_검증_작업이_예외를_던져도_게이트를_반환한다() {
		org.junit.jupiter.api.Assertions.assertThrows(
			IllegalStateException.class,
			() -> limiter.executeLoginVerification(
				"user@example.com",
				"203.0.113.18",
				() -> {
					throw new IllegalStateException("verification failed");
				}));

		assertEquals(0, limiter.activeLoginVerificationCount());
		assertTrue(
			limiter.tryAcquireLoginVerification("user@example.com", "203.0.113.18")
				.isPresent());
	}

	@Test
	void 공개_API는_null과_공백_제한_키를_거절한다() {
		assertThrows(IllegalArgumentException.class, () -> limiter.checkAndRecordSignup(null));
		assertThrows(IllegalArgumentException.class, () -> limiter.checkAndRecordLogin("  "));
		assertThrows(
			IllegalArgumentException.class,
			() -> limiter.checkLoginFailureAllowed(null, "203.0.113.20"));
		assertThrows(
			IllegalArgumentException.class,
			() -> limiter.recordLoginFailure("user@example.com", "\t"));
		assertThrows(
			IllegalArgumentException.class,
			() -> limiter.resetLoginFailures(" ", "203.0.113.20"));
		assertThrows(
			IllegalArgumentException.class,
			() -> limiter.tryAcquireLoginVerification("user@example.com", ""));
	}

	@Test
	void Retry_After는_이동_창의_남은_시간을_초_올림으로_반환한다() {
		for (int i = 0; i < 5; i++) {
			limiter.checkAndRecordSignup("203.0.113.21");
		}

		clock.advance(Duration.ofMillis(1));
		assertEquals(10, limiter.checkAndRecordSignup("203.0.113.21").retryAfterSeconds());

		clock.advance(Duration.ofMillis(9_998));
		assertEquals(1, limiter.checkAndRecordSignup("203.0.113.21").retryAfterSeconds());
	}

	@Test
	void 동시_회원가입_확인은_허용량만큼만_성공한다() throws Exception {
		int requests = 20;
		ExecutorService executor = Executors.newFixedThreadPool(requests);
		CountDownLatch ready = new CountDownLatch(requests);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<RateLimitDecision>> futures = new ArrayList<>();
			for (int i = 0; i < requests; i++) {
				futures.add(
					executor.submit(
						() -> {
							ready.countDown();
							start.await();
							return limiter.checkAndRecordSignup("203.0.113.17");
						}));
			}
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			long allowed = futures.stream().filter(future -> get(future).allowed()).count();
			assertEquals(5, allowed);
		} finally {
			executor.shutdownNow();
		}
	}

	private static RateLimitDecision get(Future<RateLimitDecision> future) {
		try {
			return future.get(5, TimeUnit.SECONDS);
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	private AuthenticationRequestProtectionProperties properties() {
		AuthenticationRequestProtectionProperties properties = new AuthenticationRequestProtectionProperties();
		properties.setWindow(Duration.ofSeconds(10));
		properties.setMaxIpKeys(5);
		properties.setMaxFailureKeys(2);
		return properties;
	}

	private static final class MutableClock extends Clock {

		private Instant current;

		private MutableClock(Instant current) {
			this.current = current;
		}

		private synchronized void advance(Duration duration) {
			current = current.plus(duration);
		}

		@Override
		public synchronized Instant instant() {
			return current;
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}
