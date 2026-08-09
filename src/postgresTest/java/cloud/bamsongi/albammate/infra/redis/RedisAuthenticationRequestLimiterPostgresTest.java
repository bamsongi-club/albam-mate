package cloud.bamsongi.albammate.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.global.config.AuthenticationRequestProtectionProperties;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiterMetrics;
import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.global.security.ratelimit.LoginVerificationPermit;

@Testcontainers
class RedisAuthenticationRequestLimiterPostgresTest {

	@Container
	static final GenericContainer REDIS = new GenericContainer("redis:8.4-alpine")
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	private LettuceConnectionFactory firstFactory;
	private LettuceConnectionFactory secondFactory;
	private AuthenticationRequestLimiter first;
	private AuthenticationRequestLimiter second;
	private String testKeyPrefix;

	@BeforeEach
	void setUp() {
		firstFactory = connectionFactory();
		secondFactory = connectionFactory();
		testKeyPrefix = "albam-mate:test:" + UUID.randomUUID() + ":auth";
		first = limiter(firstFactory, Duration.ofMillis(250));
		second = limiter(secondFactory, Duration.ofMillis(250));
	}

	@AfterEach
	void tearDown() {
		firstFactory.destroy();
		secondFactory.destroy();
	}

	@Test
	void T1_같은_IP의_signup과_login은_Redis_논리_IP_슬롯_하나를_공유한다() {
		AuthenticationRequestLimiter limited = limiter(firstFactory, Duration.ofSeconds(10), 1, 10);
		assertTrue(limited.checkAndRecordSignup("203.0.113.30").allowed());

		assertTrue(limited.checkAndRecordLogin("203.0.113.30").allowed());
		assertThrowsServiceUnavailable(() -> limited.requireSignupAllowed("203.0.113.31"));
	}

	@Test
	void T2_신규_IP는_Redis_용량_포화시_SERVICE_UNAVAILABLE로_거절된다() {
		AuthenticationRequestLimiter limited = limiter(firstFactory, Duration.ofSeconds(10), 1, 10);
		assertTrue(limited.checkAndRecordSignup("203.0.113.31").allowed());

		assertThrowsServiceUnavailable(() -> limited.requireLoginAllowed("203.0.113.32"));
	}

	@Test
	void T3_포화_뒤에도_기존_IP의_429_상태는_축출되지_않는다() {
		AuthenticationRequestLimiter limited = limiter(firstFactory, Duration.ofSeconds(10), 1, 10);
		for (int index = 0; index < 5; index++) {
			assertTrue(limited.checkAndRecordSignup("203.0.113.33").allowed());
		}

		assertThrowsServiceUnavailable(() -> limited.requireSignupAllowed("203.0.113.34"));
		assertFalse(limited.checkAndRecordSignup("203.0.113.33").allowed());
	}

	@Test
	void T4_동시_신규_IP도_원자적으로_등록_상한을_지킨다() throws Exception {
		AuthenticationRequestLimiter limited = limiter(firstFactory, Duration.ofSeconds(10), 2, 10);
		ExecutorService executor = Executors.newFixedThreadPool(8);
		CountDownLatch ready = new CountDownLatch(8);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Boolean>> futures = new ArrayList<>();
			for (int index = 0; index < 8; index++) {
				int suffix = index;
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return limited.checkAndRecordSignup("203.0.113." + (40 + suffix)).allowed();
				}));
			}
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			assertEquals(2, futures.stream().filter(this::get).count());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void T5_만료된_Redis_IP_상태는_슬롯을_반납한다() throws InterruptedException {
		AuthenticationRequestLimiter limited = limiter(firstFactory, Duration.ofMillis(250), 1, 10);
		assertTrue(limited.checkAndRecordSignup("203.0.113.51").allowed());
		assertThrowsServiceUnavailable(() -> limited.requireLoginAllowed("203.0.113.52"));
		TimeUnit.MILLISECONDS.sleep(350);

		assertTrue(limited.checkAndRecordLogin("203.0.113.52").allowed());
	}

	@Test
	void T6_실패_버킷과_gate는_Redis_실패_슬롯_하나를_공유한다() {
		AuthenticationRequestLimiter limited = limiter(firstFactory, Duration.ofSeconds(10), 10, 1);
		assertTrue(limited.recordLoginFailure("first@example.com", "203.0.113.61").allowed());
		LoginVerificationPermit permit = limited.tryAcquireLoginVerification("first@example.com", "203.0.113.61")
			.orElseThrow();
		try {
			assertThrowsServiceUnavailable(
				() -> limited.tryAcquireLoginVerification("second@example.com", "203.0.113.62"));
		} finally {
			permit.close();
		}
	}

	@Test
	void T7_실패_포화여도_확인과_반납은_허용하고_신규_gate만_거절한다() {
		AuthenticationRequestLimiter limited = limiter(firstFactory, Duration.ofSeconds(10), 10, 1);
		assertTrue(limited.recordLoginFailure("first@example.com", "203.0.113.71").allowed());

		assertTrue(limited.checkLoginFailureAllowed("second@example.com", "203.0.113.72").allowed());
		assertThrowsServiceUnavailable(
			() -> limited.tryAcquireLoginVerification("second@example.com", "203.0.113.72"));
		limited.resetLoginFailures("first@example.com", "203.0.113.71");
		assertTrue(limited.tryAcquireLoginVerification("second@example.com", "203.0.113.72").isPresent());
	}

	@Test
	void T8_gate를_먼저_해제해도_더_길게_남은_실패_TTL이_슬롯을_유지한다() throws InterruptedException {
		AuthenticationRequestLimiter limited = limiter(firstFactory, Duration.ofMillis(500), 10, 1);
		LoginVerificationPermit permit = limited.tryAcquireLoginVerification("first@example.com", "203.0.113.83")
			.orElseThrow();
		TimeUnit.MILLISECONDS.sleep(200);
		assertTrue(limited.recordLoginFailure("first@example.com", "203.0.113.83").allowed());

		permit.close();
		assertThrowsServiceUnavailable(
			() -> limited.tryAcquireLoginVerification("second@example.com", "203.0.113.84"));
		TimeUnit.MILLISECONDS.sleep(550);

		assertTrue(limited.tryAcquireLoginVerification("second@example.com", "203.0.113.84").isPresent());
	}

	@Test
	void T8_gate_해제와_실패_초기화는_남은_TTL을_갱신하고_비면_즉시_반납한다() {
		AuthenticationRequestLimiter limited = limiter(firstFactory, Duration.ofSeconds(10), 10, 1);
		assertTrue(limited.recordLoginFailure("first@example.com", "203.0.113.81").allowed());
		LoginVerificationPermit permit = limited.tryAcquireLoginVerification("first@example.com", "203.0.113.81")
			.orElseThrow();
		limited.resetLoginFailures("first@example.com", "203.0.113.81");
		assertThrowsServiceUnavailable(
			() -> limited.tryAcquireLoginVerification("second@example.com", "203.0.113.82"));

		permit.close();
		assertTrue(limited.tryAcquireLoginVerification("second@example.com", "203.0.113.82").isPresent());
	}

	@Test
	void T1_두_인스턴스의_회원가입_IP_이동창은_다섯_건만_허용한다() {
		for (int index = 0; index < 5; index++) {
			assertTrue((index % 2 == 0 ? first : second).checkAndRecordSignup("203.0.113.11").allowed());
		}

		assertFalse(second.checkAndRecordSignup("203.0.113.11").allowed());
	}

	@Test
	void T1_이동창은_경계를_넘긴_이전_이벤트만_제거한다() throws InterruptedException {
		AuthenticationRequestLimiter shortWindow = limiter(firstFactory, Duration.ofMillis(600));
		for (int index = 0; index < 4; index++) {
			assertTrue(shortWindow.checkAndRecordSignup("203.0.113.16").allowed());
		}
		TimeUnit.MILLISECONDS.sleep(350);
		assertTrue(shortWindow.checkAndRecordSignup("203.0.113.16").allowed());
		TimeUnit.MILLISECONDS.sleep(350);

		assertTrue(shortWindow.checkAndRecordSignup("203.0.113.16").allowed());
		assertTrue(shortWindow.checkAndRecordSignup("203.0.113.16").allowed());
		assertTrue(shortWindow.checkAndRecordSignup("203.0.113.16").allowed());
		assertTrue(shortWindow.checkAndRecordSignup("203.0.113.16").allowed());
		assertFalse(shortWindow.checkAndRecordSignup("203.0.113.16").allowed());
	}

	@Test
	void T2_두_인스턴스의_로그인_IP_이동창은_서른_건만_허용한다() {
		for (int index = 0; index < 30; index++) {
			assertTrue((index % 2 == 0 ? first : second).checkAndRecordLogin("203.0.113.12").allowed());
		}

		assertFalse(second.checkAndRecordLogin("203.0.113.12").allowed());
	}

	@Test
	void T3_공유_실패_bucket은_초과를_막고_성공후_초기화한다() {
		for (int index = 0; index < 5; index++) {
			AuthenticationRequestLimiter limiter = index % 2 == 0 ? first : second;
			assertTrue(limiter.checkLoginFailureAllowed("user@example.com", "203.0.113.13").allowed());
			assertTrue(limiter.recordLoginFailure("user@example.com", "203.0.113.13").allowed());
		}
		assertFalse(second.checkLoginFailureAllowed("user@example.com", "203.0.113.13").allowed());

		first.resetLoginFailures("user@example.com", "203.0.113.13");
		assertTrue(second.checkLoginFailureAllowed("user@example.com", "203.0.113.13").allowed());
	}

	@Test
	void T4_공유_로그인_게이트는_동시에_하나만_허용한다() {
		LoginVerificationPermit permit = first.tryAcquireLoginVerification("user@example.com", "203.0.113.14")
			.orElseThrow();
		try {
			assertTrue(second.tryAcquireLoginVerification("user@example.com", "203.0.113.14").isEmpty());
		} finally {
			permit.close();
		}
	}

	@Test
	void T5_만료된_gate의_이전_소유자는_새_gate를_해제하지_못한다() throws InterruptedException {
		LoginVerificationPermit stalePermit = first.tryAcquireLoginVerification("user@example.com", "203.0.113.15")
			.orElseThrow();
		LoginVerificationPermit currentPermit = awaitNewGate();
		try {
			stalePermit.close();
			assertTrue(first.tryAcquireLoginVerification("user@example.com", "203.0.113.15").isEmpty());
		} finally {
			currentPermit.close();
		}
	}

	@Test
	void T7_Redis_명령_불능_뒤_복구되면_같은_limiter가_기존_공유_제한_상태를_다시_판정한다() throws Exception {
		AuthenticationRequestLimiter longWindow = limiter(firstFactory, Duration.ofSeconds(30));
		for (int index = 0; index < 5; index++) {
			assertTrue(longWindow.checkAndRecordSignup("203.0.113.17").allowed());
		}
		pauseRedisCommands();
		awaitServiceUnavailable(longWindow);
		awaitSharedBucketRejection(longWindow);
	}

	private void pauseRedisCommands() throws Exception {
		org.testcontainers.containers.Container.ExecResult result = REDIS.execInContainer(
			"redis-cli", "CLIENT", "PAUSE", "1500", "ALL");
		assertEquals(0, result.getExitCode(), result.getStderr());
	}

	private void awaitServiceUnavailable(AuthenticationRequestLimiter limiter) {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadlineNanos) {
			try {
				limiter.checkAndRecordSignup("203.0.113.17");
			} catch (BusinessException exception) {
				if (exception.getErrorCode() == ErrorCode.SERVICE_UNAVAILABLE) {
					return;
				}
				throw exception;
			}
			awaitRetryInterval();
		}
		throw new AssertionError("Redis 중단 뒤 5초 안에 SERVICE_UNAVAILABLE이 발생하지 않았습니다");
	}

	private void awaitSharedBucketRejection(AuthenticationRequestLimiter limiter) {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadlineNanos) {
			try {
				if (!limiter.checkAndRecordSignup("203.0.113.17").allowed()) {
					return;
				}
			} catch (BusinessException exception) {
				if (exception.getErrorCode() != ErrorCode.SERVICE_UNAVAILABLE) {
					throw exception;
				}
			}
			awaitRetryInterval();
		}
		throw new AssertionError("Redis 복구 뒤 10초 안에 기존 공유 bucket 제한을 다시 판정하지 못했습니다");
	}

	private void awaitRetryInterval() {
		try {
			TimeUnit.MILLISECONDS.sleep(100);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Redis 상태 대기 중 인터럽트됐습니다", exception);
		}
	}

	private LoginVerificationPermit awaitNewGate() throws InterruptedException {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadlineNanos) {
			var permit = second.tryAcquireLoginVerification("user@example.com", "203.0.113.15");
			if (permit.isPresent()) {
				return permit.get();
			}
			TimeUnit.MILLISECONDS.sleep(25);
		}
		throw new AssertionError("gate TTL이 5초 안에 만료되지 않았습니다");
	}

	private AuthenticationRequestLimiter limiter(LettuceConnectionFactory connectionFactory, Duration window) {
		return limiter(connectionFactory, window, 10_000, 10_000);
	}

	private AuthenticationRequestLimiter limiter(LettuceConnectionFactory connectionFactory, Duration window,
		int maxIpKeys, int maxFailureKeys) {
		AuthenticationRequestProtectionProperties properties = new AuthenticationRequestProtectionProperties();
		properties.setWindow(window);
		properties.setMaxIpKeys(maxIpKeys);
		properties.setMaxFailureKeys(maxFailureKeys);
		StandardEnvironment environment = new StandardEnvironment();
		environment.setActiveProfiles("local");
		RedisAuthenticationRequestLimiter limiter = new RedisAuthenticationRequestLimiter(
			connectionFactory,
			environment,
			properties,
			AuthenticationRequestLimiterMetrics.global());
		ReflectionTestUtils.setField(limiter, "keyPrefix", testKeyPrefix);
		return limiter;
	}

	private void assertThrowsServiceUnavailable(org.junit.jupiter.api.function.Executable executable) {
		BusinessException exception = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
			executable);
		assertEquals(ErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
	}

	private boolean get(Future<Boolean> future) {
		try {
			return future.get(5, TimeUnit.SECONDS);
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	private LettuceConnectionFactory connectionFactory() {
		LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
			.commandTimeout(Duration.ofMillis(250))
			.build();
		LettuceConnectionFactory factory = new LettuceConnectionFactory(
			new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
			clientConfiguration);
		factory.setShareNativeConnection(false);
		factory.afterPropertiesSet();
		factory.start();
		return factory;
	}
}
