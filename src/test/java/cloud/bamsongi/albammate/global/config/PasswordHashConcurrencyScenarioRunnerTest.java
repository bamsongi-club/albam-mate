package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordHashConcurrencyScenarioRunnerTest {

    private final PasswordEncoder encoder = new FastPasswordEncoder();

    @Test
    void 동시성_1_4_5에서_허용량과_초과_즉시_거절_및_슬롯_반환을_검증한다() {
        PasswordHashBenchmarkReport.ConcurrencyScenarioResult one =
                PasswordHashConcurrencyScenarioRunner.run(encoder, 4, 1, 1);
        PasswordHashBenchmarkReport.ConcurrencyScenarioResult four =
                PasswordHashConcurrencyScenarioRunner.run(encoder, 4, 4, 1);
        PasswordHashBenchmarkReport.ConcurrencyScenarioResult five =
                PasswordHashConcurrencyScenarioRunner.run(encoder, 4, 5, 1);

        assertScenario(one, 1, 0);
        assertScenario(four, 4, 0);
        for (int attempt = 0; attempt < 20; attempt++) {
            PasswordHashBenchmarkReport.ConcurrencyScenarioResult repeatedFive =
                    PasswordHashConcurrencyScenarioRunner.run(encoder, 4, 5, 1);
            assertScenario(repeatedFive, 4, 1);
        }
        assertScenario(five, 4, 1);
    }

    @Test
    void timeout에서도_작업을_취소하고_데몬_워커를_정리한다() {
        CountDownLatch releaseEncoder = new CountDownLatch(1);
        PasswordEncoder blockingEncoder =
                new FastPasswordEncoder() {
                    @Override
                    public String encode(CharSequence rawPassword) {
                        try {
                            releaseEncoder.await();
                        } catch (InterruptedException exception) {
                            // Intentionally keep waiting to exercise daemon cleanup.
                            Thread.currentThread().interrupt();
                        }
                        return super.encode(rawPassword);
                    }
                };

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                PasswordHashConcurrencyScenarioRunner.run(
                                        blockingEncoder, 1, 1, 1, Duration.ofMillis(100)));
        releaseEncoder.countDown();

        assertEquals("password hash scenario timed out", exception.getMessage());
    }

    private void assertScenario(
            PasswordHashBenchmarkReport.ConcurrencyScenarioResult scenario,
            int expectedAllowed,
            int expectedRejected) {
        assertEquals(expectedAllowed, scenario.allowedCount());
        assertEquals(expectedRejected, scenario.rejectedImmediatelyCount());
        assertEquals(expectedAllowed, scenario.hashExecutionCount());
        assertTrue(scenario.allSlotsReturned());
        assertTrue(scenario.expectedCountsObserved());
    }

    private static class FastPasswordEncoder implements PasswordEncoder {

        @Override
        public String encode(CharSequence rawPassword) {
            return "{bcrypt}benchmark-hash";
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return "{bcrypt}benchmark-hash".equals(encodedPassword);
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            return false;
        }
    }
}
