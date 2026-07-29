package cloud.bamsongi.albammate.auth.security;

import java.util.UUID;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * 인증 상태가 바뀐 뒤 이전 쿠키 토큰을 다시 검증하지 않도록 세션 범위를 확인한다.
 *
 * <p>세션 없이 발급한 토큰은 {@code A:<token>} 형식이며 세션이 없는 요청에서만 검증한다. 세션 범위 토큰은
 * {@code S:<nonce>:<token>} 형식이며, 현재 세션의 nonce와 일치할 때만 검증한다.
 */
@RequiredArgsConstructor
public final class InvalidatingCsrfTokenRepository implements CsrfTokenRepository {

	static final String SESSION_NONCE_ATTRIBUTE = InvalidatingCsrfTokenRepository.class.getName() + ".SESSION_NONCE";
	private static final String ANONYMOUS_SCOPE = "A";
	private static final String SESSION_SCOPE = "S";
	private static final char SCOPE_SEPARATOR = ':';

	@NonNull private final CsrfTokenRepository delegate;

	@Override
	public CsrfToken generateToken(HttpServletRequest request) {
		CsrfToken delegateToken = delegate.generateToken(request);
		HttpSession session = request.getSession(false);
		String scope;
		if (session == null) {
			scope = ANONYMOUS_SCOPE;
		} else {
			String nonce = sessionNonce(session);
			scope = SESSION_SCOPE + SCOPE_SEPARATOR + nonce;
		}
		return new DefaultCsrfToken(
			delegateToken.getHeaderName(),
			delegateToken.getParameterName(),
			scope + SCOPE_SEPARATOR + delegateToken.getToken());
	}

	@Override
	public void saveToken(
		CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
		if (token == null) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				session.removeAttribute(SESSION_NONCE_ATTRIBUTE);
			}
		}
		delegate.saveToken(token, request, response);
	}

	@Override
	public CsrfToken loadToken(HttpServletRequest request) {
		CsrfToken token = delegate.loadToken(request);
		if (token == null) {
			return null;
		}
		if (isValidScope(token.getToken(), request)) {
			return token;
		}
		return null;
	}

	private boolean isValidScope(String token, HttpServletRequest request) {
		ScopedToken scopedToken = ScopedToken.parse(token);
		if (scopedToken == null) {
			return false;
		}
		if (ANONYMOUS_SCOPE.equals(scopedToken.scopeType())) {
			return request.getSession(false) == null;
		}
		if (SESSION_SCOPE.equals(scopedToken.scopeType())) {
			HttpSession session = request.getSession(false);
			return session != null
				&& scopedToken.scopeValue().equals(session.getAttribute(SESSION_NONCE_ATTRIBUTE));
		}
		return false;
	}

	private record ScopedToken(String scopeType, String scopeValue) {

		private static ScopedToken parse(String token) {
			if (token == null) {
				return null;
			}
			int firstSeparator = token.indexOf(SCOPE_SEPARATOR);
			if (firstSeparator <= 0) {
				return null;
			}

			String scopeType = token.substring(0, firstSeparator);
			if (ANONYMOUS_SCOPE.equals(scopeType)) {
				if (token.indexOf(SCOPE_SEPARATOR, firstSeparator + 1) >= 0
					|| token.length() <= firstSeparator + 1) {
					return null;
				}
				return new ScopedToken(scopeType, null);
			}

			int secondSeparator = token.indexOf(SCOPE_SEPARATOR, firstSeparator + 1);
			if (secondSeparator <= firstSeparator + 1) {
				return null;
			}
			return new ScopedToken(scopeType, token.substring(firstSeparator + 1, secondSeparator));
		}
	}

	private String sessionNonce(HttpSession session) {
		Object existing = session.getAttribute(SESSION_NONCE_ATTRIBUTE);
		if (existing instanceof String nonce && !nonce.isEmpty()) {
			return nonce;
		}
		String nonce = UUID.randomUUID().toString();
		session.setAttribute(SESSION_NONCE_ATTRIBUTE, nonce);
		return nonce;
	}
}
