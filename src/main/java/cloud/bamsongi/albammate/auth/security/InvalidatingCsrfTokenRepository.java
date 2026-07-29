package cloud.bamsongi.albammate.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/** 인증 상태가 바뀐 뒤 이전 쿠키 토큰을 다시 검증하지 않도록 세션 범위를 확인한다. */
@RequiredArgsConstructor
public final class InvalidatingCsrfTokenRepository implements CsrfTokenRepository {

    static final String SESSION_NONCE_ATTRIBUTE =
            InvalidatingCsrfTokenRepository.class.getName() + ".SESSION_NONCE";

    @NonNull private final CsrfTokenRepository delegate;

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        CsrfToken delegateToken = delegate.generateToken(request);
        HttpSession session = request.getSession(false);
        String scope;
        if (session == null) {
            scope = "A";
        } else {
            String nonce = sessionNonce(session);
            scope = "S:" + nonce;
        }
        return new DefaultCsrfToken(
                delegateToken.getHeaderName(),
                delegateToken.getParameterName(),
                scope + ":" + delegateToken.getToken());
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
        if (token == null || isValidScope(token.getToken(), request)) {
            return token;
        }
        return null;
    }

    private boolean isValidScope(String token, HttpServletRequest request) {
        if (token == null) {
            return false;
        }
        int firstSeparator = token.indexOf(':');
        int secondSeparator = firstSeparator < 0 ? -1 : token.indexOf(':', firstSeparator + 1);
        if (firstSeparator <= 0) {
            return false;
        }

        String scopeType = token.substring(0, firstSeparator);
        if ("A".equals(scopeType)) {
            return secondSeparator < 0
                    && token.length() > firstSeparator + 1
                    && request.getSession(false) == null;
        }
        if (secondSeparator <= firstSeparator + 1) {
            return false;
        }
        String scopeValue = token.substring(firstSeparator + 1, secondSeparator);
        if (!"S".equals(scopeType)) {
            return false;
        }
        HttpSession session = request.getSession(false);
        return session != null && scopeValue.equals(session.getAttribute(SESSION_NONCE_ATTRIBUTE));
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
