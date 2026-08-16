package cloud.bamsongi.albammate.auth.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.security.web.context.SecurityContextRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class AppSessionEstablisherTest {

	@Test
	void 필수_보안_컨텍스트_저장소가_null이면_생성_즉시_실패한다() {
		assertThrows(NullPointerException.class, () -> new AppSessionEstablisher(null));
	}

	@Test
	void 보안_컨텍스트를_저장소에_저장한다() {
		SecurityContextRepository repository = mock(SecurityContextRepository.class);
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		AppSessionEstablisher establisher = new AppSessionEstablisher(repository);

		establisher.discard(request, response);

		verify(repository).saveContext(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(request),
			org.mockito.ArgumentMatchers.eq(response));
	}
}
