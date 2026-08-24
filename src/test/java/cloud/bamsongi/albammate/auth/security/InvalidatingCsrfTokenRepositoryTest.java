package cloud.bamsongi.albammate.auth.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class InvalidatingCsrfTokenRepositoryTest {

	private final CsrfTokenRepository delegate = Mockito.mock(CsrfTokenRepository.class);
	private final InvalidatingCsrfTokenRepository repository = new InvalidatingCsrfTokenRepository(delegate);

	@Test
	void 익명_요청에서_생성한_토큰은_세션없이만_읽는다() {
		MockHttpServletRequest anonymousRequest = new MockHttpServletRequest();
		when(delegate.generateToken(anonymousRequest))
			.thenReturn(new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "delegate"));

		CsrfToken generated = repository.generateToken(anonymousRequest);
		when(delegate.loadToken(anonymousRequest)).thenReturn(generated);

		assertEquals("A:delegate", generated.getToken());
		assertSame(generated, repository.loadToken(anonymousRequest));

		MockHttpServletRequest sessionRequest = new MockHttpServletRequest();
		sessionRequest.setSession(new MockHttpSession());
		when(delegate.loadToken(sessionRequest)).thenReturn(generated);
		assertNull(repository.loadToken(sessionRequest));
	}

	@Test
	void 세션_토큰은_같은_nonce를_재사용하고_다른_세션에서는_읽지_않는다() {
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setSession(session);
		when(delegate.generateToken(request))
			.thenReturn(new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "first"))
			.thenReturn(new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "second"));

		CsrfToken first = repository.generateToken(request);
		CsrfToken second = repository.generateToken(request);
		when(delegate.loadToken(request)).thenReturn(first);

		assertSame(first, repository.loadToken(request));
		assertEquals(scope(first.getToken()), scope(second.getToken()));

		MockHttpServletRequest otherRequest = new MockHttpServletRequest();
		otherRequest.setSession(new MockHttpSession());
		when(delegate.loadToken(otherRequest)).thenReturn(first);
		assertNull(repository.loadToken(otherRequest));
	}

	@Test
	void 형식이_잘못된_토큰은_세션과_익명_요청에서_읽지_않는다() {
		for (String invalid : new String[] {
			null, "", "A", "A:", "A:one:two", "S:nonce", "S::value", "X:nonce:value"
		}) {
			MockHttpServletRequest request = new MockHttpServletRequest();
			when(delegate.loadToken(request)).thenReturn(token(invalid));

			assertNull(repository.loadToken(request));
		}
	}

	@Test
	void 토큰을_삭제하면_세션_nonce도_삭제하고_delegate에_전달한다() {
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setSession(session);
		when(delegate.generateToken(request))
			.thenReturn(new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "delegate"));
		CsrfToken generated = repository.generateToken(request);
		Object previousNonce = session.getAttribute(InvalidatingCsrfTokenRepository.SESSION_NONCE_ATTRIBUTE);

		MockHttpServletResponse response = new MockHttpServletResponse();
		repository.saveToken(null, request, response);

		assertNotEquals(null, previousNonce);
		assertNull(session.getAttribute(InvalidatingCsrfTokenRepository.SESSION_NONCE_ATTRIBUTE));
		verify(delegate).saveToken(null, request, response);
		assertEquals("S", generated.getToken().substring(0, 1));
	}

	private String scope(String token) {
		int secondSeparator = token.indexOf(':', token.indexOf(':') + 1);
		return token.substring(0, secondSeparator);
	}

	private CsrfToken token(String value) {
		return new CsrfToken() {
			@Override
			public String getHeaderName() {
				return "X-XSRF-TOKEN";
			}

			@Override
			public String getParameterName() {
				return "_csrf";
			}

			@Override
			public String getToken() {
				return value;
			}
		};
	}
}
