package cloud.bamsongi.albammate.auth.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.security.web.context.SecurityContextRepository;

import cloud.bamsongi.albammate.measurement.AuthNotificationMeasurementRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class AppSessionEstablisherTest {

	@Test
	void T12_필수_보안_컨텍스트_저장소가_null이면_생성_즉시_실패한다() {
		assertThrows(NullPointerException.class, () -> new AppSessionEstablisher(null, null));
	}

	@Test
	void T5_보안_컨텍스트_저장은_세션_저장소와_별도_단계로_기록한다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		SecurityContextRepository repository = mock(SecurityContextRepository.class);
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		AppSessionEstablisher establisher = new AppSessionEstablisher(
			repository, new AuthNotificationMeasurementRecorder(registry));

		establisher.discard(request, response);

		verify(repository).saveContext(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(request),
			org.mockito.ArgumentMatchers.eq(response));
		assertEquals(1,
			registry.find("auth.login.stage.duration").tag("stage", "session-context-save").timer().count());
	}
}
