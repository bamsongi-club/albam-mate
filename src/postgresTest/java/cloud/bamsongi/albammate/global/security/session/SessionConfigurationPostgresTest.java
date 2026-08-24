package cloud.bamsongi.albammate.global.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest
class SessionConfigurationPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private MapSessionRepository sessionRepository;

	@Test
	void postgresTest_프로필은_Redis_없이_30분_인메모리_세션으로_기동한다() {
		MapSession session = sessionRepository.createSession();

		assertEquals(Duration.ofMinutes(30), session.getMaxInactiveInterval());
	}
}
