package cloud.bamsongi.albammate.global.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.bamsongi.albammate.global.config.AuthenticationRequestProtectionProperties;
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
    void 실패_버킷은_낮은_빈도부터_축출되고_상한을_넘지_않는다() {
        limiter.recordLoginFailure("low@example.com", "203.0.113.13");
        for (int i = 0; i < 3; i++) {
            limiter.recordLoginFailure("hot@example.com", "203.0.113.14");
        }
        limiter.recordLoginFailure("new@example.com", "203.0.113.15");

        assertEquals(2, limiter.loginFailureBucketCount());
        assertTrue(limiter.checkLoginFailureAllowed("hot@example.com", "203.0.113.14").allowed());
        assertTrue(limiter.checkLoginFailureAllowed("new@example.com", "203.0.113.15").allowed());
        assertTrue(limiter.checkLoginFailureAllowed("low@example.com", "203.0.113.13").allowed());
    }

    @Test
    void 동일한_로그인_키는_검증_게이트를_하나만_획득한다() {
        LoginVerificationPermit first =
                limiter.tryAcquireLoginVerification("user@example.com", "203.0.113.16")
                        .orElseThrow();

        Optional<LoginVerificationPermit> second =
                limiter.tryAcquireLoginVerification("user@example.com", "203.0.113.16");

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
                () ->
                        limiter.executeLoginVerification(
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
        AuthenticationRequestProtectionProperties properties =
                new AuthenticationRequestProtectionProperties();
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
