package cloud.bamsongi.albammate.global.security.password;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.global.config.AuthenticationRequestProtectionProperties;

class InMemoryPasswordHashConcurrencyLimiterTest {

	@Test
	void 기본_해시_슬롯은_4개이고_모두_반환하면_다시_획득할_수_있다() {
		AuthenticationRequestProtectionProperties properties = new AuthenticationRequestProtectionProperties();
		InMemoryPasswordHashConcurrencyLimiter limiter = new InMemoryPasswordHashConcurrencyLimiter(properties);
		List<PasswordHashPermit> permits = new ArrayList<>();

		for (int i = 0; i < 4; i++) {
			permits.add(limiter.tryAcquire().orElseThrow());
		}

		assertEquals(4, limiter.currentConcurrent());
		assertTrue(limiter.tryAcquire().isEmpty());
		permits.get(0).close();
		permits.get(0).close();
		assertEquals(3, limiter.currentConcurrent());
		PasswordHashPermit replacement = limiter.tryAcquire().orElseThrow();
		replacement.close();
		permits.subList(1, permits.size()).forEach(PasswordHashPermit::close);
		assertEquals(0, limiter.currentConcurrent());
	}
}
